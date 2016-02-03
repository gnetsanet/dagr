/*
 * The MIT License
 *
 * Copyright (c) 2015 Fulcrum Genomics LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package dagr.core.cmdline.parsing

import java.lang.reflect.{Field, InvocationTargetException, Modifier}
import java.nio.file.{Path, Paths}

import dagr.core.cmdline.{ClassFinder, CLPAnnotation, _}
import dagr.core.tasksystem.Pipeline
import dagr.core.util.ReflectionUtil

import scala.collection.JavaConversions._
import scala.collection.immutable.Map
import scala.collection.mutable.ListBuffer

/** Variables and Methods to support command line parsing */
private[parsing] object ParsingUtil {
  /** Returns true if the class is a subclass of [[Seq]] */
  def isSeqClass(clazz: Class[_]): Boolean = classOf[scala.collection.Seq[_]].isAssignableFrom(clazz)

  /** Returns true if the class is a subclass of [[Set]] */
  def isSetClass(clazz: Class[_]): Boolean = classOf[scala.collection.Set[_]].isAssignableFrom(clazz)

  /** Returns true if the class is a subclass of [[java.util.Collection]] */
  def isJavaCollectionClass(clazz: Class[_]): Boolean = classOf[java.util.Collection[_]].isAssignableFrom(clazz)

  /** Returns true if the class is subclass of a collection class ([[Seq]], [[Set]] or [[java.util.Collection]]) */
  def isCollectionClass(clazz: Class[_]): Boolean = isJavaCollectionClass(clazz) || isSeqClass(clazz) || isSetClass(clazz)

  /** Returns true if the class of the field is a subclass of a collection class ([[Seq]], [[Set]] or [[java.util.Collection]]) */
  def isCollectionField(field: Field): Boolean = isCollectionClass(field.getType)

  /** True if the value is a collection class and is empty, false otherwise.  Throws a [[IllegalArgumentException]] if
    * the value is not a supported collection class ([[Seq]], [[Set]] or [[java.util.Collection]]).
    */
  def isCollectionEmpty(value: Any): Boolean = {
    if (isJavaCollectionClass(value.getClass)) value.asInstanceOf[java.util.Collection[_]].isEmpty
    else if (isSeqClass(value.getClass)) value.asInstanceOf[Seq[_]].isEmpty
    else if (isSetClass(value.getClass)) value.asInstanceOf[Set[_]].isEmpty
    else throw new IllegalArgumentException(s"Could not determine collection type of '${value.getClass.getSimpleName}")
  }

  /** Gets the annotation of type `T` of the class `clazz` */
  def getAnnotation[T <: java.lang.annotation.Annotation](clazz: Class[_], annotationClazz: Class[T]): T = {
    clazz.getAnnotation(annotationClazz)
  }

  /** Gets the [[CLPAnnotation]] annotation on this class */
  def getProgramProperty(clazz: Class[_]): CLPAnnotation = {
    getAnnotation(clazz, classOf[CLPAnnotation])
  }

  /** Initializes the color for printing */
  private def initializeColor(color: String): String = {
    if (CommandLineDefaults.COLOR_STATUS) color
    else ""
  }

  /** Provides ANSI colors for the terminal output **/
  val KNRM: String = initializeColor("\u001B[0m")
  //val KBLD: String = initializeColor("\u001B[1m")
  val KRED: String = initializeColor("\u001B[31m")
  val KGRN: String = initializeColor("\u001B[32m")
  val KYEL: String = initializeColor("\u001B[33m")
  val KBLU: String = initializeColor("\u001B[34m")
  val KMAG: String = initializeColor("\u001B[35m")
  val KCYN: String = initializeColor("\u001B[36m")
  val KWHT: String = initializeColor("\u001B[37m")
  val KBLDRED: String = initializeColor("\u001B[1m\u001B[31m")
  val KERROR: String = initializeColor("\u001B[1m\u001B[31m")
  //val KERROR: String = initializeColor("\u001B[35;7;1m") // BLINKING: "\u001B[35;5;1m"

  /** Useful for testing */
  private[cmdline] def getClassToPropertyMapFromSourceClasses(srcClasses: Traversable[Class[_]],
                                                              omitSubClassesOf: Iterable[Class[_]] = Nil,
                                                              includeClassesOmittedFromCommandLine: Boolean = false)
  : Map[PipelineClass, CLPAnnotation] = {

    // Filter out interfaces, synthetic, primitive, local, or abstract classes.
    def keepCommandLineTaskClass(clazz: Class[_]): Boolean = {
      !clazz.isInterface && !clazz.isSynthetic && !clazz.isPrimitive && !clazz.isLocalClass && !Modifier.isAbstract(clazz.getModifiers)
    }

    // Find all classes with the annotation
    val classes = srcClasses
      .filter { keepCommandLineTaskClass }
      .filterNot { clazz => omitSubClassesOf.exists { _.isAssignableFrom(clazz) } }
      .filter { clazz =>
        // find the [[CLPAnnotation]] annotation
        val annotation = getProgramProperty(clazz)
        annotation != null && (includeClassesOmittedFromCommandLine || !annotation.omitFromCommandLine)
      }.map(_.asInstanceOf[PipelineClass])

    // Get all the name collisions
    var nameCollisions = new ListBuffer[String]()
    classes.groupBy(_.getSimpleName).foreach { case (k, v) =>
      if (v.size > 1) nameCollisions += v.mkString(", ")
    }

    // SimpleName should be unique
    if (nameCollisions.nonEmpty) {
      throw new CommandLineException(s"Simple class name collision: ${nameCollisions.mkString(", ")}")
    }

    // Finally, make the map
    classes.map(clazz => Tuple2(clazz, getAnnotation(clazz, classOf[CLPAnnotation]))).toMap
  }


  /** Gets a mapping between sub-classes of CommandLineTask and their properties.
  *
  * Throws a [[IllegalStateException]] if a sub-class of CommandLineTask is missing the
  * [[CLPAnnotation]] annotation.
  *
  * @param packageList the namespace(s) to search.
  * @return map from clazz to property.
  */
  def findPipelineClasses(packageList: List[String],
                          omitSubClassesOf: Iterable[Class[_]] = Nil,
                          includeClassesOmittedFromCommandLine: Boolean = false)
  : Map[PipelineClass, CLPAnnotation] = {

    // find all classes that extend CommandLineTask
    val classFinder: ClassFinder = new ClassFinder
    for (pkg <- packageList) {
      classFinder.find(pkg, classOf[Pipeline])
    }

    getClassToPropertyMapFromSourceClasses(
      srcClasses = classFinder.getClasses,
      omitSubClassesOf = omitSubClassesOf,
      includeClassesOmittedFromCommandLine = includeClassesOmittedFromCommandLine)
  }

  /**
    * Constructs one or more objects from Strings.
    *
    * @param resultType the type that must be assignable to, from the return of this function (may be a collection or a single-valued type)
    * @param unitType either the same as resultType or when resultType is a collection, the type of elements in the collection
    * @param value one or more strings from which to construct objects.  More than one string should only be given for collections.
    * @return an object constructed from the string.
    */
  def constructFromString(resultType: Class[_], unitType: Class[_], value: String*): Any = {
    if (resultType != unitType && !isCollectionClass(resultType) && !classOf[Option[_]].isAssignableFrom(resultType)) {
      throw new CommandLineParserInternalException("Don't know how to make a " + resultType.getSimpleName)
    }

    try {
      getFromString(resultType, unitType, value:_*)
    }
    catch {
      case e: NoSuchMethodException =>
        throw new CommandLineParserInternalException(s"Cannot find string ctor for '${unitType.getSimpleName}'", e)
      case e: InstantiationException =>
        throw new CommandLineParserInternalException(s"Abstract class '${unitType.getSimpleName} cannot be used for an argument value type.", e)
      case e: IllegalAccessException =>
        throw new CommandLineParserInternalException(s"String constructor for argument class ''${unitType.getSimpleName}'' must be public.", e)
      case e: InvocationTargetException =>
        throw new BadArgumentValue(s"Problem constructing '${unitType.getSimpleName}' from the string${plural(value.size)} '" + value.toList.mkString(", ") + "'.")
    }
  }

  /**
    * Attempts to construct one or more unitType values from Strings, and return them as the resultType. Handles
    * the creation of and packaging into collections as necessary.
    */
  private[parsing] def getFromString(resultType: Class[_], unitType: Class[_], value: String*): Any = {
    resultType match {
      case clazz if isCollectionClass(clazz) =>
        val typedValues = value.map(v => getUnitFromStringBuilder(unitType, v)).asInstanceOf[Seq[java.lang.Object]]

        // Condition for the collection type
        if (isJavaCollectionClass(clazz)) {
          try {
            ReflectionUtil.newJavaCollectionInstance(clazz.asInstanceOf[Class[java.util.Collection[AnyRef]]], typedValues)
          }
          catch {
            case e: IllegalArgumentException =>
              throw new CommandLineParserInternalException(s"Collection of type '${resultType.getSimpleName}' cannot be constructed or auto-initialized with a known type.")
          }
        }
        else if (isSeqClass(clazz) || isSetClass(clazz)) {
          ReflectionUtil.newScalaCollection(clazz.asInstanceOf[Class[_ <: Iterable[_]]], typedValues)
        }
        else {
          throw new CommandLineParserInternalException(s"Unknown collection type '${resultType.getSimpleName}'")
        }
      case clazz =>
        if (value.size != 1) throw new CommandLineException(s"Expecting a single argument to convert to ${resultType.getSimpleName} but got ${value.size}")
        getUnitOrOptionFromString(resultType, unitType, value.head)
    }
  }



  /**
    * Supports any class with a string constructor, [[Enum]], and [[java.nio.file.Path]].  If the class is an [[Option]],
    * it will either return `None` if `s == null`, or `Some` object wrapping the an object of the wrapped type.
    */
  private def getUnitOrOptionFromString(resultType: Class[_], unitType: Class[_], value: String): Any = {
    val unit = getUnitFromStringBuilder(unitType, value)
    if (resultType == classOf[Option[_]]) Option(unit)
    else unit
  }

  /** Attempts to construct a single value of unitType from the provided String and return it. */
  private def getUnitFromStringBuilder(unitType: Class[_], value: String) : Any = {
    ReflectionUtil.ifPrimitiveThenWrapper(unitType) match {
      case clazz if clazz.isEnum =>
        lazy val badArgumentString = s"'$value' is not a valid value for ${clazz.getSimpleName}. " + ArgumentDefinitionPrinting.getEnumOptions(clazz.asInstanceOf[Class[_ <: Enum[_ <: Enum[_]]]])
        val maybeEnum = clazz.getEnumConstants.map(_.asInstanceOf[Enum[_]]).find(e => e.name() == value)
        maybeEnum.getOrElse(throw new BadArgumentValue(badArgumentString))
      case clazz if clazz == classOf[Path] =>
        Paths.get(value)
      // unitType shouldn't be an option, since it's supposed to the type *inside* any containers
      case clazz if clazz == classOf[Option[_]] =>
        throw new CommandLineParserInternalException(s"Cannot construct an '${unitType.getSimpleName}' from a string.  Is this an Option[Option[...]]?")
      case clazz =>
        if (clazz == classOf[java.lang.Object]) value
        else {
          import java.lang.reflect.Constructor
          val ctor: Constructor[_] = clazz.getDeclaredConstructor(classOf[String])
          ctor.setAccessible(true)
          ctor.newInstance(value)
        }
    }
  }

  /**
    * Returns the string "s" if x is greater than 1.
    *
    * @param x Value to test.
    * @return "s" if x is greater than one else "".
    */
  def plural(x: Int) = if (x > 1) "s" else ""
}