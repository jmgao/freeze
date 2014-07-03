package us.insolit.freeze.test

import scala.language.experimental.macros
import org.scalatest.FunSuite
import org.scalatest.Assertions._
import us.insolit.freeze._

class Root extends Cloneable

@freeze
class Parent(var a: String, var b: String) extends Root {
  var c: String = "c"
  val untouched: Int = 1

  def this(a: String) = this(a, "b")

  override def toString() = "Parent(%s, %s, %s, %d)".format(a, b, c, untouched)
}


@freezeChild
class Child(private var d: String, override val untouched: Int) extends Parent("child a", "child b") {
  override def toString() = "Child(%s, %s, %s, %s, %d)".format(a, b, c, d, untouched)
}

class FreezeTest extends FunSuite {
  test("@freeze") {
    val parent = new Parent("a", "b")

    assert(parent.toString() == "Parent(a, b, c, 1)")

    val mutatedParent = parent.clone(f => {
      f.a = "mutated a"
      f.c = "mutated c"
    })

    assert(parent.toString == "Parent(a, b, c, 1)")
    assert(mutatedParent.toString == "Parent(mutated a, b, mutated c, 1)")
  }

  test("@freezeChild - child") {
    val child = new Child("d", 123)
    assert(child.toString == "Child(child a, child b, c, d, 123)")

    val mutatedChild = child.clone((c: Child#Mutable) => {
      c.a = "mutated a"
      c.c = "mutated c"
      c.d = "mutated d"
    })

    assert(child.toString == "Child(child a, child b, c, d, 123)")
    assert(mutatedChild.toString == "Child(mutated a, child b, mutated c, mutated d, 123)")
  }

  test("@freezeChild - parent") {
    val child = new Child("d", 123)
    assert(child.toString == "Child(child a, child b, c, d, 123)")

    val childAsParent: Parent = child
    assert(childAsParent.toString == "Child(child a, child b, c, d, 123)")

    val mutatedChildAsParent = childAsParent.clone(c => {
      c.a = "child as parent a"
    })
    assert(childAsParent.toString == "Child(child a, child b, c, d, 123)")
    assert(mutatedChildAsParent.toString == "Child(child as parent a, child b, c, d, 123)")
  }
}
