## freeze
A Scala macro library to simplify the use of immutability in classes. freeze provides annotations
that transform a class containing mutable members into a class that hides the mutable backing `var`s,
and provides a `clone` method which takes a lambda which allows mutation.

### Usage
Tag classes with `@freeze` or `@freezeChild`, depending on whether they inherit from a class which has already has a freeze annotation on it. This will add a `clone` method that takes a `Mutable` clone of the original object.

```scala
foo.clone(fooClone => { fooClone.bar = 123 })
```
is transformed into something roughly equivalent to:
```scala
{
  val cloneTemp = foo.clone()
  (fooClone => { fooClone.bar = 123 })(cloneTemp.asInstanceOf[Foo#Mutable])
  cloneTemp
}
```

See [the tests](test/src/main/scala/FreezeTest.scala) for further examples.

### Implementation
`@freeze` effectively performs the following transformation:

```scala
//
// Before
//
@freeze
class Parent(var a: Int) {
  var b: Int = 0

  def sum = a + b
}

@freezeChild
class Child(private var c: Int, val constant_value: Int) extends Parent(1)

//
// After
//
class Parent(private var freeze$a: Int) implements scala.Cloneable {
  private var freeze$b: Int = 0

  def a = freeze$a
  def b = freeze$b

  def sum = a + b

  def clone(mutator: Test#Mutable => Any) = macro freeze.cloneImpl
  override protected def clone(): Test = super.clone().asInstanceOf[Test]
  class Mutable {
    def a = freeze$a
    def b = freeze$b
    def a_=(value: Int) = { freeze$a = value }
    def b_=(value: Int) = { freeze$b = value }
  }
}

class Child(private var freeze$c: Int, val constant_value: Int) extends Parent(1) {
  private def c = freeze$c

  def clone(mutator: Child#Mutable => Any) = macro freeze.cloneImpl
  override protected def clone(): Test = super.clone().asInstanceOf[Test]
  class Mutable extends super.Mutable {
    /* public */ def c = freeze$c
    /* public */ def c_=(value: Int) = { freeze$c = value }
  }
}
```

### Known issues
* You must use `@freezeChild` in a class inheriting from a frozen parent if you wish to mutate the parent's `var`s from an instance of the child.
* Invocations of `clone` on a class that either is, or derives from a class annotated with `@freezeChild` must explicitly specify the type of the `Mutable` argument.
* Methods of the original class cannot be called on the `Mutable` instance given by `clone`.
* Currently, only classes can be frozen. Extending the macro to apply to traits as well is in progress.
* Getters and setters inside the `Mutable` inner class have their accessibility modifiers stripped (i.e. they are public), in order to allow them to be accessed from the macro instantiation.

### Known issues (WONTFIX)
* Name collisions will happen if you use names that begin with `freeze$`, or use the names Mutable or clone. Don't do this.
