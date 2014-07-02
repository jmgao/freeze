package us.insolit.freeze

import scala.reflect.macros._
import scala.language.experimental.macros
import scala.annotation.StaticAnnotation

class freeze extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro freeze.annotationImpl
}

class freezeChild extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro freeze.annotationImpl
}

object freeze {
  def mangleName(name: String) = "freeze$" + name

  def annotationImpl(c: blackbox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._
    import c.universe.Flag._

    case class Mangled(mangledName: String, modifiers: Modifiers, typename: TypeName, isParameter: Boolean)

    def filterModifiers(modifiers: Modifiers, predicate: FlagSet => Boolean): FlagSet = {
      val interestingModifiers = Set(IMPLICIT, FINAL, PRIVATE, PROTECTED, SEALED, OVERRIDE, CASE, ABSTRACT, DEFERRED, MUTABLE, PARAM, ABSOVERRIDE, LOCAL, SYNTHETIC, LAZY, PARAMACCESSOR)
      val declaredModifiers = interestingModifiers.filter(modifiers.hasFlag(_)).filter(predicate)
      declaredModifiers.fold(NoFlags)(_ | _)
    }

    //
    // Find all of the variables in the body of a class
    //
    def findVars(template: Template) = {
      val Template(_, _, body) = template
      var mangling = Map[String, Mangled]()

      body foreach {
        definition => definition match {
          case ValDef(modifiers, TermName(name), tpt, rhs) => {
            val Modifiers(flags, privateWithin, annotations) = modifiers

            if ((flags.## & MUTABLE.##) == MUTABLE.##) {
              if (tpt.isEmpty) {
                c.abort(definition.pos, "@freeze requires explicit type declarations for all vars")
              }

              val typename = tpt.asInstanceOf[Ident].name.asInstanceOf[TypeName]

              val isParameter = (flags.## & PARAMACCESSOR.##) == PARAMACCESSOR.##
              mangling = mangling + (name -> Mangled(mangleName(name), modifiers, typename, isParameter))
            }
          }

          case _ => {}
        }
      }

      mangling
    }

    //
    // Mangle the original names in the body
    //
    def mangleNames(template: Template)(implicit mangling: Map[String, Mangled]) = {
      val Template(parents, self, body) = template

      val mangledBody = body map {
        case ValDef(modifiers, TermName(name), tpt, rhs) => {
          mangling.get(name).map(_.mangledName) match {
            case Some(properName) => {
              val Modifiers(flags, privateWithin, annotations) = modifiers

              val accessibiltyFlags = Set(PRIVATE, PROTECTED)
              val filteredFlags = filterModifiers(modifiers, !accessibiltyFlags.contains(_))
              ValDef(Modifiers(filteredFlags | SYNTHETIC | PRIVATE, TypeName(""), annotations), TermName(properName), tpt, rhs)
            }

            case None => {
              ValDef(modifiers, TermName(name), tpt, rhs)
            }
          }
        }

        case other => other
      }

      Template(parents, self, mangledBody)
    }

    //
    // Fix constructor parameter names
    //
    def fixConstructor(template: Template)(implicit mangling: Map[String, Mangled]) = {
      val Template(parents, self, body) = template

      // Hack, assume that the default constructor is always the first one in the AST
      var foundConstructor = false
      val mangledBody = body map {
        case defDef: DefDef => {
          val DefDef(modifiers, name, tparams, vparamss, tpt, rhs) = defDef

          if (!foundConstructor && name == termNames.CONSTRUCTOR) {
            foundConstructor = true

            if (vparamss.size != 1) {
              c.abort(defDef.pos, "<init> has multiple vparamss")
            }

            val fixedParameters = vparamss(0) map {
              parameter => {
                val ValDef(modifiers, TermName(name), tpt, rhs) = parameter

                val finalName = mangling.get(name).map(_.mangledName).getOrElse(name)
                ValDef(modifiers, TermName(finalName), tpt, rhs)
              }
            }

            DefDef(modifiers, name, tparams, List(fixedParameters), tpt, rhs)
          } else {
            defDef
          }
        }

        case other => other
      }

      Template(parents, self, mangledBody)
    }

    //
    // Generate getters
    //
    def generateGetters(stripAccessibility: Boolean = false)(implicit mangling: Map[String, Mangled]) = {
      var getters = List[DefDef]()

      mangling.toTraversable.foreach {
        case (original, mangled) => {
          val getterName = TermName(original)
          val originalType = mangled.typename
          val mangledName = TermName(mangled.mangledName)

          val predicate = {
            flag: FlagSet => {
              if (stripAccessibility) {
                val accessibiltyFlags = Set(PRIVATE, PROTECTED)
                !accessibiltyFlags.contains(flag)
              } else {
                true
              }
            }
          }
          var modifiers = filterModifiers(mangled.modifiers, predicate)
          getters = q"$modifiers def $getterName: $originalType = $mangledName" :: getters
        }
      }

      getters
    }

    //
    // Generate setters
    //
    def generateSetters(stripAccessibility: Boolean)(implicit mangling: Map[String, Mangled]) = {
      var setters = List[DefDef]()

      mangling.toTraversable.foreach {
        case (original, mangled) => {
          val setterName = TermName(original + "_$eq")
          val originalType = mangled.typename
          val mangledName = TermName(mangled.mangledName)


          val predicate = {
            flag: FlagSet => {
              if (stripAccessibility) {
                val accessibiltyFlags = Set(PRIVATE, PROTECTED)
                !accessibiltyFlags.contains(flag)
              } else {
                true
              }
            }
          }
          var modifiers = filterModifiers(mangled.modifiers, predicate)
          setters =
            q"""
              $modifiers def $setterName(rhs: $originalType) = {
                $mangledName = rhs
              }
            """ :: setters
        }
      }
      setters
    }

    //
    // Add getters to the body
    //
    def addGetters(template: Template)(implicit mangling: Map[String, Mangled]) = {
      val Template(parents, self, body) = template
      Template(parents, self, body ++ generateGetters())
    }

    //
    // Add inner class to the body
    //
    def addInnerClass(template: Template, typename: TypeName, child: Boolean)(implicit mangling: Map[String, Mangled]) = {
      val Template(parents, self, body) = template

      val getters = generateGetters(true);
      val setters = generateSetters(true);
      val cloneSuper = {
        if (child) {
          q"override def freeze$$clone(): $typename = $typename.this.clone().asInstanceOf[$typename]"
        } else {
          q"def freeze$$clone(): $typename = $typename.this.clone().asInstanceOf[$typename]"
        }
      }

      val innerClass = {
        if (child) {
          q"""
            class Mutable extends super.Mutable {
              ..$getters
              ..$setters
              $cloneSuper
            }
          """
        } else {
          q"""
            class Mutable {
              ..$getters
              ..$setters
              $cloneSuper
            }
          """
        }
      }

      Template(parents, self, body ++ Seq(innerClass))
    }

    def addClone(template: Template, typename: TypeName) = {
      val Template(parents, self, body) = template
      val cloneMacro = {
        q"def clone(mutator: $typename#Mutable => Any): $typename = macro us.insolit.freeze.freeze.cloneImpl[$typename#Mutable]"
      }
      val cloneSuper = {
        q"override protected def clone(): $typename = super.clone().asInstanceOf[$typename]"
      }

      Template(parents ++ Seq(Select(Ident(TermName("scala")), TypeName("Cloneable"))), self, body ++ Seq(cloneMacro, cloneSuper))
    }

    //
    // Do the macro transformation
    //

    val annotationChild = c.prefix.tree match {
      case q"new $annotationType()" => {
        annotationType.toString.endsWith("freezeChild")
      }
      case _ => c.abort(c.enclosingPosition, "Unknown @freeze annotation")
    }

    val inputs = annottees.map(_.tree).toList
    val (annottee, expandees) = inputs match {
      case (param: ValDef) :: (rest @ (_ :: _)) => (param, rest)
      case (param: TypeDef) :: (rest @ (_ :: _)) => (param, rest)
      case _ => (EmptyTree, inputs)
    }

    if (!annottee.isEmpty) {
      c.abort(c.enclosingPosition, "@freeze annotation on invalid argument")
    }

    val transformedExpandee = expandees map {
      case ClassDef(modifiers, typename, tparams, template) => {
        implicit val nameMangling = findVars(template)

        var transformedTemplate = template

        transformedTemplate = mangleNames(transformedTemplate)
        transformedTemplate = fixConstructor(transformedTemplate)
        transformedTemplate = addGetters(transformedTemplate)
        transformedTemplate = addInnerClass(transformedTemplate, typename, annotationChild)
        transformedTemplate = addClone(transformedTemplate, typename)

        ClassDef(modifiers, typename, tparams, transformedTemplate)
      }

      case unknown => {
        c.abort(c.enclosingPosition, "Unknown definition: " + unknown)
      }
    }
    c.Expr[Any](Block(transformedExpandee, Literal(Constant(()))))
  }

  def cloneImpl[T: c.WeakTypeTag](c: blackbox.Context)(mutator: c.Expr[T => Any]): c.Expr[T] = {
    import c.universe._
    import c.universe.Flag._

    val prefix = c.prefix.tree

    val cloneName = Ident(TermName(c.freshName("freeze$clonedTemp$")))

    c.Expr[T](
      q"""
      {
        val $cloneName = new $prefix.Mutable().freeze$$clone()
        ($mutator)(new $cloneName.Mutable)
        $cloneName
      }"""
    )
  }
}
