package carueda.cfg

import scala.collection.immutable.Seq
import scala.meta._

/**
  * This annotation allows to specify the schema of your application or library
  * configuration using plain old case classes and inner vals and objects.
  * It generates an `apply(c: com.typesafe.config.Config)` method in the companion
  * object to instantiate your case class with a given Typesafe Config object.
  * <br>
  * <br>
  * Example:
  * {{{
  * @ Cfg
  * case class ExampleCfg(num: Int, str: String = "foo", dur: Option[Duration])
  * ...
  * val cfg = ExampleCfg(ConfigFactory.parseString("num = 1"))
  * assert( cfg.num == 1 )
  * assert( cfg.str == "foo" )
  * assert( cfg.dur.isEmpty )
  * }}}
  */
class Cfg extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    defn match {
      case Term.Block(Seq(cls @ Defn.Class(mods, name, _, _, _),
           companion: Defn.Object)) if mods.exists(_.is[Mod.Case]) ⇒
        CfgUtil.handleCaseClass(cls, name, Some(companion))

      case cls @ Defn.Class(mods, name, _, _, _) if mods.exists(_.is[Mod.Case]) ⇒
        CfgUtil.handleCaseClass(cls, name)

      case _ ⇒
        //println(defn.structure)
        abort("@Cfg must annotate a case class")
    }
  }
}

private object CfgUtil {
  def handleCaseClass(cls: Defn.Class, typeName: Type.Name,
                      companionOpt: Option[Defn.Object] = None,
                      level: Int = 0
                     ): Term.Block = {

    val Defn.Class(
    _,
    name,
    _,
    ctor,
    template@Template(_, _, _, statsOpt)
    ) = cls

    val cn = typeName.syntax + "." + memberConfigName

    var templateStats: List[Stat] = List.empty

    var clsNeedJavaConverters = false

    for (stats ← statsOpt) stats foreach {
      case obj:Defn.Object ⇒
        val (stat, nc) = handleObj(obj, cn, level + 1)
        templateStats :+= stat
        clsNeedJavaConverters = clsNeedJavaConverters || nc

      case v:Defn.Val ⇒
        val (stats, nc) = handleVal(v, cn)
        templateStats ++= stats
        clsNeedJavaConverters = clsNeedJavaConverters || nc

      case stat ⇒
        templateStats :+= stat
    }

    val hasBodyElements = templateStats.nonEmpty
    val (applyMethod, objNeedConverters) = createApply(name, ctor.paramss, hasBodyElements)
    val decl = q"private var ${Pat.Var.Term(memberConfigTermName)}: _root_.com.typesafe.config.Config = _"

    val newCompanion = companionOpt match {
      case None ⇒
        var stats: List[Stat] = List.empty
        if (objNeedConverters)
          stats :+= importJavaConverters
        if (hasBodyElements)
          stats :+= decl
        stats :+= applyMethod
        q"""
            object ${Term.Name(name.value)} {
              ..$stats
            }
        """
      case Some(companion) ⇒
        var newStats: List[Stat] = List.empty
        if (objNeedConverters)
          newStats :+= importJavaConverters
        if (hasBodyElements)
          newStats :+= decl
        newStats :+= applyMethod
        val stats: Seq[Stat] = newStats ++ companion.templ.stats.getOrElse(Nil)
        companion.copy(templ = companion.templ.copy(stats = Some(stats)))
    }

    val clsStats = if (clsNeedJavaConverters) importJavaConverters :: templateStats else templateStats

    Term.Block(Seq(
      cls.copy(templ = template.copy(stats = Some(clsStats))),
      newCompanion))
  }

  private def createApply(name: Type.Name,
                          paramss: Seq[Seq[Term.Param]],
                          hasBodyElements: Boolean
                         ): (Defn.Def, Boolean) = {

    var needJavaConverters = false

    def getGetter(param: Term.Param): Term = {
      val name = unbacktick(param.name.syntax)
      // condition in case of with-default or Option:
      val cond = Term.Name(s"""$paramConfigName.hasPath("$name")""")

      val declType = param.decltpe.get

      val actualGetter: Term = declType match {
        case Type.Apply(Type.Name("Option"), Seq(typ)) ⇒
          val (t, nc) = basicOrObjectGetter(paramConfigName, name, typ)
          needJavaConverters = needJavaConverters || nc
          q"""if ($cond) Some($t) else None"""

        case Type.Apply(Type.Name("List"), Seq(argType)) ⇒
          needJavaConverters = true
          val argArg = Lit.String(name)

          val listElement = listElementAccessor(argType)
          argType match {
            case Type.Apply(Type.Name("List"), _) ⇒
              q"""$paramConfigTermName.getAnyRefList($argArg).asScala.toList.map(
                 _.asInstanceOf[_root_.java.util.ArrayList[_]]).map($listElement)"""

            case _ ⇒
              q"""$paramConfigTermName.getAnyRefList($argArg).asScala.toList.map(
                 $listElement)"""
          }

        case _ if isBasic(declType.syntax) ⇒
          Term.Name(s"""$paramConfigName.get$declType("$name")""")

        case _ if declType.syntax == "Duration" ⇒
          Term.Name(
            s"""_root_.scala.concurrent.duration.Duration.fromNanos(
               |$paramConfigName.getDuration("$name").toNanos)""".stripMargin)

        case _ if isSizeInBytes(declType.syntax) ⇒
          Term.Name(s"""$paramConfigName.getBytes("$name")""")

        case _ ⇒
          val arg = Term.Name(s"""$paramConfigName.getConfig("$name")""")
          val constructor = Ctor.Ref.Name(declType.syntax)
          q"$constructor($arg)"
      }

      param.default match {
        case None ⇒
          actualGetter

        case Some(default) ⇒
          q"""if ($cond) $actualGetter else $default"""
      }
    }

    val args = paramss.map(_.map(getGetter))
    val ctor = q"${Ctor.Ref.Name(name.value)}(...$args)"
    val defn = if (hasBodyElements)
      q"""
          def apply($paramConfigTermName: _root_.com.typesafe.config.Config): $name = {
            $memberConfigTermName = $paramConfigTermName
            $ctor
          }
      """
    else
      q"""
          def apply($paramConfigTermName: _root_.com.typesafe.config.Config): $name = {
            $ctor
          }
      """

    (defn, needJavaConverters)
  }

  private def listElementAccessor(elementType: Type): Term = elementType match {
    case Type.Apply(Type.Name("Option"), Seq(_)) ⇒
      abort("Option only valid at first level in the type")

    case Type.Apply(Type.Name("List"), Seq(argType)) ⇒
      val listElement = listElementAccessor(argType)
      argType match {
        case Type.Apply(Type.Name("List"), _) ⇒
          q"""_.asScala.toList.map(_.asInstanceOf[_root_.java.util.ArrayList[_]]).map(
             $listElement)"""

        case _ ⇒
          q"""_.asScala.toList.map($listElement)"""
      }

    case _ if isBasic(elementType.syntax) ⇒
      Term.Name(s"""_.asInstanceOf[$elementType]""")

    case _ if elementType.syntax == "Duration" ⇒
      q"""v => _root_.scala.concurrent.duration.Duration.fromNanos(
         _root_.com.typesafe.config.ConfigFactory.parseString(
         "d = " + v).getDuration("d").toNanos)"""

    case _ if elementType.syntax == "SizeInBytes" ⇒
      q"""v => _root_.com.typesafe.config.ConfigFactory.parseString(
         "d = " + v).getBytes("d").asInstanceOf[SizeInBytes]"""

    case _ ⇒
      val constructor = Ctor.Ref.Name(elementType.syntax)
      q"h => $constructor($hashMapToConfig)"
  }

  private def handleVal(v: Defn.Val, cn: String): (List[Stat], Boolean) = {
    val Defn.Val(_, pats, Some(declTpe), rhs) = v

    var needJavaConverters = false

    def getGetter(t: Pat.Var.Term, name: String): Term = {
      val cond = Term.Name(s"""$cn.hasPath("$name")""")

      val actualGetter: Term = declTpe match {
        case Type.Apply(Type.Name("Option"), Seq(argType)) ⇒
          val (t, nc) = basicOrObjectGetter(cn, name, argType)
          needJavaConverters = needJavaConverters || nc
          q"""if ($cond) Some($t) else None"""

        case Type.Apply(Type.Name("List"), Seq(argType)) ⇒
          needJavaConverters = true
          val listElement = listElementAccessor(argType)
          argType match {
            case Type.Apply(Type.Name("List"), _) ⇒
              q"""${Term.Name(cn)}.getAnyRefList(${Lit.String(name)}).asScala.toList.map(
                 _.asInstanceOf[_root_.java.util.ArrayList[_]]).map($listElement)"""

            case _ ⇒
              q"""${Term.Name(cn)}.getAnyRefList(${Lit.String(name)}).asScala.toList.map(
                 $listElement)"""
          }

        case _ if isBasic(declTpe.syntax) ⇒
          Term.Name(s"""$cn.get${declTpe.syntax}("$name")""")

        case _ if declTpe.syntax == "Duration" ⇒
          Term.Name(
            s"""_root_.scala.concurrent.duration.Duration.fromNanos(
               |$cn.getDuration("$name").toNanos)""".stripMargin)

        case _ if isSizeInBytes(declTpe.syntax) ⇒
          Term.Name(s"""$cn.getBytes("$name")""")

        case _ ⇒
          val arg = Term.Name(s"""$cn.getConfig("$name")""")
          val constructor = Ctor.Ref.Name(declTpe.syntax)
          q"$constructor($arg)"
      }

      if (rhs.syntax == "$")
        actualGetter
      else {
        q"""if ($cond) $actualGetter else $rhs"""
      }
    }

    var templateStats: List[Stat] = List.empty
    pats foreach {
      case t@Pat.Var.Term(Term.Name(name)) ⇒
        val getter = getGetter(t, unbacktick(name))
        templateStats :+= q"""val $t: $declTpe = $getter"""
    }
    (templateStats, needJavaConverters)
  }

  private def basicOrObjectGetter(cn: String, name: String, typ: Type): (Term, Boolean) = {
    var needConverters = false
    val term = typ match {
      case Type.Apply(Type.Name("Option"), Seq(_)) ⇒
        abort("Option only valid at first level in the type: " + name)

      case Type.Apply(Type.Name("List"), Seq(argType)) ⇒
        needConverters = true
        val argArg = Lit.String(name)
        val listElement = listElementAccessor(argType)
        argType match {
          case Type.Apply(Type.Name("List"), _) ⇒
            q"""${Term.Name(cn)}.getAnyRefList($argArg).asScala.toList.map(
                 _.asInstanceOf[_root_.java.util.ArrayList[_]]).map($listElement)"""

          case _ ⇒
            q"""${Term.Name(cn)}.getAnyRefList($argArg).asScala.toList.map(
                 $listElement)"""
        }

      case _ if isBasic(typ.syntax) ⇒
        Term.Name(s"""$cn.get${typ.syntax}("$name")""")

      case _ if typ.syntax == "Duration" ⇒
        Term.Name(
          s"""_root_.scala.concurrent.duration.Duration.fromNanos(
             |$cn.getDuration("$name").toNanos)""".stripMargin)

      case _ if isSizeInBytes(typ.syntax) ⇒
        Term.Name(s"""$cn.getBytes("$name")""")

      case _ ⇒
        val arg = Term.Name(s"""$cn.getConfig("$name")""")
        val constructor = Ctor.Ref.Name(typ.syntax)
        q"$constructor($arg)"
    }
    (term, needConverters)
  }

  private def handleObj(obj: Defn.Object, cn: String, level: Int): (Stat, Boolean) = {
    val Defn.Object(_, name, template@Template(_, _, _, Some(stats))) = obj

    var templateStats: List[Stat] = List.empty

    val newCn = {
      val dollar = "$" * level
      val str = {
        if (hasBackticks(name.syntax))
          s"`$dollar${unbacktick(name.syntax)}`"
        else
          s"`$dollar${name.syntax}`"
      }
      Pat.Var.Term(Term.Name(str))
    }
    val getter = Term.Name(s"""$cn.getConfig("${unbacktick(name.syntax)}")""")
    templateStats :+= q"""private val $newCn = $getter"""

    var needConverters = false
    stats foreach {
      case obj:Defn.Object ⇒
        val (stat, nc) = handleObj(obj, newCn.syntax, level + 1)
        templateStats :+= stat
        needConverters = needConverters || nc

      case v:Defn.Val ⇒
        val (stats, nc) = handleVal(v, newCn.syntax)
        templateStats ++= stats
        needConverters = needConverters || nc

      case stat ⇒
        templateStats :+= stat
    }

    (obj.copy(templ = template.copy(stats = Some(templateStats))), needConverters)
  }

  private def isBasic(typ: String): Boolean =
    Set("String", "Int", "Boolean", "Double", "Long"
    ).contains(typ)

  private def isSizeInBytes(typ: String): Boolean = typ == "SizeInBytes"

  private def hasBackticks(name: String): Boolean = name.startsWith("`")

  private def unbacktick(name: String): String = name.replaceAll("^`|`$", "")

  private val paramConfigName  = "$cp"  // name of apply Config parameter
  private val memberConfigName = "$cm"  // corresp member variable name (for access to case class)

  private val paramConfigTermName  = Term.Name(paramConfigName)
  private val memberConfigTermName = Term.Name(memberConfigName)

  private val importJavaConverters = q"import scala.collection.JavaConverters._"

  private val hashMapToConfig: Term.Apply =
    q"""_root_.com.typesafe.config.ConfigFactory.parseMap(
       h.asInstanceOf[_root_.java.util.HashMap[String, _]])"""
}
