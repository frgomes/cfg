package carueda.cfg

import scala.collection.immutable.Seq
import scala.meta._

class Cfg extends scala.annotation.StaticAnnotation {
  inline def apply(defn: Any): Any = meta {
    defn match {
      case Term.Block(Seq(cls @ Defn.Class(mods, name, _, _, _),
           companion: Defn.Object)) if mods.exists(_.is[Mod.Case]) ⇒
        CfgUtil.handleCaseClass(cls, name.syntax + ".$c", Some(companion))

      case cls @ Defn.Class(mods, name, _, _, _) if mods.exists(_.is[Mod.Case]) ⇒
        CfgUtil.handleCaseClass(cls, name.syntax + ".$c")

      case _ ⇒
        //println(defn.structure)
        abort("@Cfg must annotate a case class")
    }
  }
}

private object CfgUtil {

  def handleCaseClass(cls: Defn.Class, cn: String,
                      companionOpt: Option[Defn.Object] = None, level: Int = 0): Term.Block = {
    val Defn.Class(
    _,
    name,
    _,
    ctor,
    template@Template(_, _, _, statsOpt)
    ) = cls

    var templateStats: List[Stat] = List.empty

    for (stats ← statsOpt) stats foreach {
      case obj:Defn.Object ⇒
        templateStats :+= CfgUtil.handleObj(obj, cn, level + 1)

      case v:Defn.Val ⇒
        templateStats ++= CfgUtil.handleVal(v, cn)
    }

    val hasBodyElements = templateStats.nonEmpty
    val applyMethod = createApply(name, ctor.paramss, hasBodyElements)
    val decl = q"private var ${Pat.Var.Term(Term.Name("$c"))}: com.typesafe.config.Config = _"

    val newCompanion = companionOpt match {
      case None ⇒
        if (hasBodyElements)
          q"""
              object ${Term.Name(name.value)} {
                $decl
                $applyMethod
              }
          """
        else
          q"""
              object ${Term.Name(name.value)} {
                $applyMethod
              }
          """

      case Some(companion) ⇒
        var newStats: List[Stat] = List(applyMethod)
        if (hasBodyElements)
          newStats = decl +: newStats
        val stats: Seq[Stat] = newStats ++ companion.templ.stats.getOrElse(Nil)
        companion.copy(templ = companion.templ.copy(stats = Some(stats)))
    }

    Term.Block(Seq(
      cls.copy(templ = template.copy(stats = Some(templateStats))),
      newCompanion))
  }

  private def createApply(name: Type.Name, paramss: Seq[Seq[Term.Param]], hasBodyElements: Boolean): Defn.Def = {
    def getGetter(param: Term.Param): Term = {
      // condition in case of with-default or Option:
      val cond = Term.Name(s"""c.hasPath("${param.name}")""")

      val declType = param.decltpe.get
      //println("createApply: " +name+ "::" +param.name+
      // " declType = " + declType.syntax + " param.default=" +param.default)

      val actualGetter: Term = {
        if (isBasic(declType.syntax)) {
          Term.Name("c.get" + declType + s"""("${param.name}")""")
        }
        else isOptionOf(declType) match {
          case Some(typ) ⇒
            q"""if ($cond) Some(${basicOrObjectGetter("c", param.name.syntax, typ)}) else None"""

          case None ⇒
            val arg = Term.Name(s"""c.getConfig("${param.name.syntax}")""")
            val constructor = Ctor.Ref.Name(declType.syntax)
            q"$constructor($arg)"
        }
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
    if (hasBodyElements)
      q"""
          def apply(c: com.typesafe.config.Config): $name = {
            ${Term.Name("$c")} = c
            $ctor
          }
      """
    else
      q"""
          def apply(c: com.typesafe.config.Config): $name = {
            $ctor
          }
      """
  }

  private def handleVal(v: Defn.Val, cn: String): List[Stat] = {
    val Defn.Val(_, pats, Some(declTpe), rhs) = v

    //println("handleVal: cn=" +cn+ "  " + pats.structure +
    // " declTpe=" + declTpe.structure + "  rhs=" + rhs)

    def getGetter(t: Pat.Var.Term, name: String): Term = {
      val cond = Term.Name(cn + s""".hasPath("$name")""")

      val actualGetter: Term = {
        if (isBasic(declTpe.syntax)) {
          Term.Name(cn + ".get" + declTpe.syntax + s"""("$name")""")
        }
        else isOptionOf(declTpe) match {
          case Some(typ) ⇒
            q"""if ($cond) Some(${basicOrObjectGetter(cn, name, typ)}) else None"""

          case None ⇒
            val arg = Term.Name(s"""$cn.getConfig("$name")""")
            val constructor = Ctor.Ref.Name(declTpe.syntax)
            q"$constructor($arg)"
        }
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
        val getter = getGetter(t, name)
        templateStats :+= q"""val $t: $declTpe = $getter"""
    }
    templateStats
  }

  private def basicOrObjectGetter(cn: String, name: String, typ: Type): Term = {
    if (isBasic(typ.syntax)) {
      Term.Name(cn + ".get" + typ + s"""("$name")""")
    }
    else if (isOptionOf(typ).isDefined) {
      abort("Option only valid at first level in the type: " + name)
    }
    else {
      val arg = Term.Name(cn + s""".getConfig("$name")""")
      val constructor = Ctor.Ref.Name(typ.syntax)
      q"$constructor($arg)"
    }
  }

  private def handleObj(obj: Defn.Object, cn: String, level: Int = 0): Stat = {
    val Defn.Object(_, name, template@Template(_, _, _, Some(stats))) = obj
    //println("handleObj:    " + name.structure)

    var templateStats: List[Stat] = List.empty

    val newCn = Pat.Var.Term(Term.Name(("$" * level) + name.syntax))
    val getter = Term.Name(s"""$cn.getConfig("${name.syntax}")""")
    templateStats :+= q"""private val $newCn = $getter"""

    stats foreach {
      case obj:Defn.Object ⇒
        templateStats :+= CfgUtil.handleObj(obj, newCn.syntax, level + 1)

      case v:Defn.Val ⇒
        templateStats ++= CfgUtil.handleVal(v, newCn.syntax)
    }

    obj.copy(templ = template.copy(stats = Some(templateStats)))
  }

  private def isBasic(typ: String): Boolean =
    Set("String", "Int", "Boolean", "Double", "Long"
    ).contains(typ)

  private def isOptionOf(typ: Type.Arg): Option[Type] = typ match {
    case Type.Apply(Type.Name("Option"), Seq(of)) ⇒
      Some(of)

    case _ ⇒ None
  }
}
