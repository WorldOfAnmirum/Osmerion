/*
 * Copyright (c) 2017 Leon Linhart,
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.github.themrmilchmann.osmerion.internal.generator.java

import com.github.themrmilchmann.osmerion.internal.generator.*
import java.io.*
import java.lang.reflect.*
import java.util.*
import java.util.stream.*

private val CATEGORY = "(\\d+)\\Q_\\E(.*)".toRegex()

private val WEIGHT_FIELD = 0
private val WEIGHT_CONSTRUCTOR = 1
private val WEIGHT_METHOD = 2
private val WEIGHT_SUBTYPE = Integer.MAX_VALUE

interface Member : Comparable<Member> {

    var category: String
    val name: String

    fun getWeight(): Int

    fun PrintWriter.printMember(indent: String): Boolean

    override fun compareTo(other: Member) = name.compareTo(other.name)

}

abstract class JavaType(
    fileName: String,
    packageName: String,
    moduleName: String,
    kind: String
): GeneratorTarget(fileName, "java", packageName, moduleName, kind), Member, IType {

    override val simpleName = fileName

    private val annotations = mutableListOf<Annotation>()
    internal val body = TreeSet<Member> {
        alpha, beta ->

        val aC = alpha.category
        val bC = beta.category
        val cC = aC.compareTo(bC)
        val aW = alpha.getWeight()
        val bW = beta.getWeight()

        if (cC != 0) cC
        else if (aW > bW) 1
        else if (aW < bW) -1
        else alpha.compareTo(beta)
    }
    private val imports = TreeSet<Import>()
    internal val typeParameters = mutableListOf<JavaTypeParameter>()

    override var category: String = ""
    var authors: Array<out String>? = null
    var see: Array<out String>? = null
    var documentation: String = ""
    var since: String = ""

    fun authors(vararg authors: String) {
        this@JavaType.authors = authors
    }

    fun see(vararg see: String) {
        this@JavaType.see = see
    }

    fun addAnnotations(vararg annotations: Annotation) {
        this@JavaType.annotations.addAll(annotations)
    }

    fun addImport(import: Import) {
        if (import is StaticImport || (import.packageName != "java.lang" && import.packageName != packageName)) {
            val occurrences = imports.stream().filter { i -> import.compareTo(i) == 0}.collect(Collectors.toList())

            if (occurrences.size > 5) {
                this@JavaType.imports.removeAll(occurrences)

                import.typeQualifier = "*"
                this@JavaType.imports.add(import)
            } else if (!(occurrences.size == 1 && occurrences[0].typeQualifier == "*")) {
                this@JavaType.imports.add(import)
            }
        }
    }

    internal fun addImport(type: IType) = addImport(Import(type))

    override final fun PrintWriter.printMember(indent: String): Boolean {
        printType(indent = indent)
        println()

        return false
    }

    override final fun PrintWriter.printTarget() {
        println(COPYRIGHT_HEADER)
        println("package $packageName;")
        println()

        var specialImportId: String = ""

        if (imports.any()) {
            imports.forEach {
                if (specialImportId.isNotEmpty() && !it.packageName.startsWith(specialImportId)) println()

                if (it.packageName.startsWith("java.")) specialImportId = "java."
                else if (it.packageName.startsWith("javax.")) specialImportId = "javax."
                else specialImportId = ""

                it.apply { printImport() }
            }

            println()
        }

        printType()
    }

    override final fun toString() = fileName

    protected fun PrintWriter.printType(indent: String = "", subIndent: String = indent + INDENT) {
        println(documentation.toJavaDoc(indent, typeParameters = typeParameters, see = see, authors = authors, since = since))

        if (annotations.isNotEmpty()) println(printAnnotations(indent = indent, annotations = annotations))

        printTypeDeclaration(indent)
        print(" {")

        if (body.isNotEmpty()) {
            println()
            println()

            var isFirst = true
            var prevCategory: String = ""
            var isLastLineBlank = true

            body.forEach {
                if (it.category.isNotEmpty()) {
                    val mCat = CATEGORY.matchEntire(it.category) ?: throw IllegalArgumentException("Category name does not match pattern")
                    val category = mCat.groupValues[2]

                    if (it.category != prevCategory && !(isFirst && category.isEmpty())) {
                        if (category.isNotEmpty()) {
                            if (!isLastLineBlank) println()

                            println("$subIndent// ${CATEGORY_DIVIDER.substring(subIndent.length + 3)}")
                            println("$subIndent// # $category ${CATEGORY_DIVIDER.substring(subIndent.length + category.length + 6)}")
                            println("$subIndent// ${CATEGORY_DIVIDER.substring(subIndent.length + 3)}")
                        }

                        println()
                    }

                    isFirst = false
                    prevCategory = it.category
                }

                it.run { isLastLineBlank = printMember(subIndent) }
            }
        }

        print("$indent}")
    }

    protected abstract fun PrintWriter.printTypeDeclaration(indent: String = "")

}

fun Profile.javaClass(
    fileName: String,
    packageName: String,
    moduleName: String,
    superClass: IType? = null,
    visibility: Int = 0,
    kind: String = KIND_MAIN,
    init: JavaClass.() -> Unit
): JavaClass {
    val v = StringBuilder().run {
        val isAbstract = Modifier.isAbstract(visibility)

        if (Modifier.isPublic(visibility)) append("public ")
        if (Modifier.isProtected(visibility)) throw IllegalArgumentException("Illegal modifier \"protected\"")
        if (Modifier.isPrivate(visibility)) throw IllegalArgumentException("Illegal modifier \"private\"")

        if (isAbstract) append("abstract ")
        if (Modifier.isStatic(visibility)) throw IllegalArgumentException("Illegal modifier \"static\"")
        if (Modifier.isFinal(visibility)) {
            if (isAbstract)
                throw IllegalArgumentException("Illegal modifier \"final\"")
            else
                append("final ")
        }
        if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
        if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
        if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
        if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
        if (Modifier.isStrict(visibility)) throw IllegalArgumentException("Illegal modifier \"strictfp\"")
        if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

        toString()
    }

    val target = JavaClass(fileName, packageName, moduleName, superClass, visibility, v, kind)
    init.invoke(target)
    this@javaClass.targets.add(target)

    return target
}

class JavaClass internal constructor(
    override val name: String,
    packageName: String,
    moduleName: String,
    val superClass: IType?,
    val intVisibility: Int,
    val visibility: String,
    kind: String
): JavaType(name, packageName, moduleName, kind = kind) {

    init {
        if (superClass != null) addImport(superClass)
    }

    private val interfaces = mutableListOf<IType>()

    fun addInterfaces(vararg interfaces: IType) {
        interfaces.forEach { addImport(it) }
        this@JavaClass.interfaces.addAll(interfaces)
    }

    fun typeParameter(type: String, documentation: String): IType {
        val t_tp = GenericType(type)
        val tp = JavaTypeParameter(t_tp, documentation)
        this@JavaClass.typeParameters.add(tp)

        return t_tp
    }

    fun constructor(
        documentation: String,
        vararg parameters: JavaParameter,
        visibility: Int = 0,
        body: String = "",
        category: String = "",
        since: String = "",
        throws: Array<out String>? = null,
        see: Array<out String>? = null,
        typeParameters: Array<out JavaTypeParameter>? = null,
        annotations: List<Annotation>? = null,
        preserveOrder: Boolean = true
    ) {
        val v = StringBuilder().run {
            if (Modifier.isPublic(visibility)) append("public ")
            if (Modifier.isProtected(visibility)) append("protected ")
            if (Modifier.isPrivate(visibility)) append("private ")

            if (Modifier.isAbstract(visibility)) throw IllegalArgumentException("Illegal modifier \"abstract\"")
            if (Modifier.isStatic(visibility)) throw IllegalArgumentException("Illegal modifier \"static\"")
            if (Modifier.isFinal(visibility)) throw IllegalArgumentException("Illegal modifier \"final\"")
            if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
            if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
            if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
            if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
            if (Modifier.isStrict(visibility)) throw IllegalArgumentException("Illegal modifier \"strictfp\"")
            if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

            toString()
        }

        val constructor = JavaConstructor(this, documentation, parameters, v, body, category, since, throws, see, typeParameters, annotations, preserveOrder)
        this@JavaClass.body.add(constructor)
    }

    fun IType.field(
        name: String,
        documentation: String,
        visibility: Int = 0,
        value: String = "",
        category: String = "",
        since: String = "",
        see: Array<out String>? = null,
        annotations: List<Annotation>? = null
    ) {
        val v = StringBuilder().run {
            val isFinal = Modifier.isFinal(visibility)

            if (Modifier.isPublic(visibility)) append("public ")
            if (Modifier.isProtected(visibility)) append("protected ")
            if (Modifier.isPrivate(visibility)) append("private ")

            if (Modifier.isAbstract(visibility)) throw IllegalArgumentException("Illegal modifier \"abstract\"")
            if (Modifier.isStatic(visibility)) append("static ")
            if (isFinal) append("final ")
            if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
            if (Modifier.isVolatile(visibility)) {
                if (isFinal)
                    throw IllegalArgumentException("Illegal modifier \"volatile\"")
                else
                    append("volatile ")
            }
            if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
            if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
            if (Modifier.isStrict(visibility)) throw IllegalArgumentException("Illegal modifier \"strictfp\"")
            if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

            toString()
        }

        addImport(this)

        val field = JavaField(this, name, documentation, v, value, category, since, see, annotations)
        this@JavaClass.body.add(field)
    }

    fun javaClass(
        fileName: String,
        packageName: String,
        moduleName: String,
        superClass: IType? = null,
        visibility: Int = 0,
        kind: String = KIND_MAIN,
        init: JavaClass.() -> Unit
    ): JavaClass {
        val v = StringBuilder().run {
            val isAbstract = Modifier.isAbstract(visibility)

            if (Modifier.isPublic(visibility)) append("public ")
            if (Modifier.isProtected(visibility)) append("protected ")
            if (Modifier.isPrivate(visibility)) append("private ")

            if (isAbstract) append("abstract ")
            if (Modifier.isStatic(visibility)) append("static ")
            if (Modifier.isFinal(visibility)) {
                if (isAbstract)
                    throw IllegalArgumentException("Illegal modifier \"final\"")
                else
                    append("final ")
            }
            if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
            if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
            if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
            if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
            if (Modifier.isStrict(visibility)) throw IllegalArgumentException("Illegal modifier \"strictfp\"")
            if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

            toString()
        }

        val target = JavaClass(fileName, packageName, moduleName, superClass, visibility, v, kind)
        init.invoke(target)
        this@JavaClass.body.add(target)

        return target
    }

    fun javaInterface(
        fileName: String,
        packageName: String,
        moduleName: String,
        visibility: Int = 0,
        kind: String = KIND_MAIN,
        init: JavaInterface.() -> Unit
    ): JavaInterface {
        val v = StringBuilder().run {
            if (Modifier.isPublic(visibility)) append("public ")
            if (Modifier.isProtected(visibility)) append("protected ")
            if (Modifier.isPrivate(visibility)) append("private ")

            if (Modifier.isAbstract(visibility)) println("WARNING: Redundant modifier \"abstract\"")
            if (Modifier.isStatic(visibility)) println("WARNING: Redundant modifier \"static\"")
            if (Modifier.isFinal(visibility)) throw IllegalArgumentException("Illegal modifier \"final\"")
            if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
            if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
            if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
            if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
            if (Modifier.isStrict(visibility)) throw IllegalArgumentException("Illegal modifier \"strictfp\"")
            // if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

            toString()
        }

        val target = JavaInterface(fileName, packageName, moduleName, visibility, v, kind)
        init.invoke(target)
        this@JavaClass.body.add(target)

        return target
    }

    fun IType.method(
        name: String,
        documentation: String,
        vararg parameters: JavaParameter,
        visibility: Int = 0,
        body: String? = null,
        category: String = "",
        returnDoc: String = "",
        since: String = "",
        throws: Array<out String>? = null,
        see: Array<out String>? = null,
        typeParameters: Array<out JavaTypeParameter>? = null,
        annotations: List<Annotation>? = null,
        preserveOrder: Boolean = true
    ) {
        val v = StringBuilder().run {
            val isAbstract = Modifier.isAbstract(visibility)

            if (Modifier.isPublic(visibility)) append("public ")
            if (Modifier.isProtected(visibility)) append("protected ")
            if (Modifier.isPrivate(visibility)) {
                if (isAbstract)
                    throw IllegalArgumentException("Illegal modifier \"private\"")
                else
                    append("private ")
            }

            if (isAbstract) {
                if (body != null)
                    throw IllegalArgumentException("\"abstract\" modifier combined with method body")
                else if (!Modifier.isAbstract(this@JavaClass.intVisibility))
                    throw IllegalArgumentException("Illegal modifier \"abstract\"")
                else
                    append("abstract ")
            }
            if (Modifier.isStatic(visibility)) {
                if (isAbstract)
                    throw IllegalArgumentException("Illegal modifier \"static\"")
                else
                    append("static ")
            }
            if (Modifier.isFinal(visibility)) {
                if (isAbstract)
                    throw IllegalArgumentException("Illegal modifier \"final\"")
                else
                    append("final ")
            }
            if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
            if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
            if (Modifier.isSynchronized(visibility)) {
                if (isAbstract)
                    throw IllegalArgumentException("Illegal modifier \"synchronized\"")
                else
                    append("synchronized ")
            }
            if (Modifier.isNative(visibility)) {
                if (isAbstract)
                    throw IllegalArgumentException("Illegal modifier \"native\"")
                else
                    append("native ")
            }
            if (Modifier.isStrict(visibility)) {
                if (isAbstract)
                    throw IllegalArgumentException("Illegal modifier \"strictfp\"")
                else
                    append("strictfp ")
            }
            if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

            toString()
        }

        addImport(this)
        parameters.forEach { addImport(it.type) }

        val method = JavaMethod(this, name, documentation, parameters, v, body, category, returnDoc, since, throws, see, typeParameters, annotations, preserveOrder)
        this@JavaClass.body.add(method)
    }

    override fun getWeight() = WEIGHT_SUBTYPE

    override fun PrintWriter.printTypeDeclaration(indent: String) {
        print(visibility)
        print("class ")
        print(fileName)

        if (typeParameters.isNotEmpty()) {
            print("<")
            print(StringJoiner(", ").apply {
                typeParameters.forEach { add(it.toString()) }
            })
            print(">")
        }

        if (superClass != null) {
            print(" extends ")
            print(superClass.toString())
        }

        if (interfaces.isNotEmpty()) {
            print(" implements ")
            print(StringJoiner(", ").apply {
                interfaces.forEach { add(it.toString()) }
            })
        }
    }

}

fun Profile.javaInterface(
    fileName: String,
    packageName: String,
    moduleName: String,
    visibility: Int = 0,
    kind: String = KIND_MAIN,
    init: JavaInterface.() -> Unit
): JavaInterface {
    val v = StringBuilder().run {
        if (Modifier.isPublic(visibility)) append("public ")
        if (Modifier.isProtected(visibility)) throw IllegalArgumentException("Illegal modifier \"protected\"")
        if (Modifier.isPrivate(visibility)) throw IllegalArgumentException("Illegal modifier \"private\"")

        if (Modifier.isAbstract(visibility)) println("WARNING: Redundant modifier \"abstract\"")
        if (Modifier.isStatic(visibility)) throw IllegalArgumentException("Illegal modifier \"static\"")
        if (Modifier.isFinal(visibility)) throw IllegalArgumentException("Illegal modifier \"final\"")
        if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
        if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
        if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
        if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
        if (Modifier.isStrict(visibility)) throw IllegalArgumentException("Illegal modifier \"strictfp\"")
        // if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

        toString()
    }

    val target = JavaInterface(fileName, packageName, moduleName, visibility, v, kind)
    init.invoke(target)
    this@javaInterface.targets.add(target)

    return target
}

class JavaInterface internal constructor(
    override val name: String,
    packageName: String,
    moduleName: String,
    val intVisibility: Int,
    val visibility: String,
    kind: String
): JavaType(name, packageName, moduleName, kind) {

    private val interfaces = mutableListOf<IType>()

    fun addInterfaces(vararg interfaces: IType) {
        interfaces.forEach { addImport(it) }
        this@JavaInterface.interfaces.addAll(interfaces)
    }

    fun typeParameter(type: String, documentation: String): IType {
        val t_tp = GenericType(type)
        val tp = JavaTypeParameter(t_tp, documentation)
        this@JavaInterface.typeParameters.add(tp)

        return t_tp
    }

    fun IType.field(
        name: String,
        documentation: String,
        visibility: Int = 0,
        value: String = "",
        category: String = "",
        since: String = "",
        see: Array<out String>? = null,
        annotations: List<Annotation>? = null
    ) {
        val v = StringBuilder().run {
            if (Modifier.isPublic(visibility)) println("WARNING: Redundant modifier \"public\"")
            if (Modifier.isProtected(visibility)) throw IllegalArgumentException("Illegal modifier \"protected\"")
            if (Modifier.isPrivate(visibility)) throw IllegalArgumentException("Illegal modifier \"private\"")

            if (Modifier.isAbstract(visibility)) throw IllegalArgumentException("Illegal modifier \"abstract\"")
            if (Modifier.isStatic(visibility)) println("WARNING: Redundant modifier \"static\"")
            if (Modifier.isFinal(visibility)) println("WARNING: Redundant modifier \"final\"")
            if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
            if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
            if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
            if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
            if (Modifier.isStrict(visibility)) throw IllegalArgumentException("Illegal modifier \"strictfp\"")
            if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

            toString()
        }

        addImport(this)

        val field = JavaField(this, name, documentation, v, value, category, since, see, annotations)
        this@JavaInterface.body.add(field)
    }

    fun javaClass(
        fileName: String,
        packageName: String,
        moduleName: String,
        superClass: IType? = null,
        visibility: Int = 0,
        kind: String = KIND_MAIN,
        init: JavaClass.() -> Unit
    ): JavaClass {
        val v = StringBuilder().run {
            val isAbstract = Modifier.isAbstract(visibility)

            if (Modifier.isPublic(visibility)) append("public ")
            if (Modifier.isProtected(visibility)) throw IllegalArgumentException("Illegal modifier \"protected\"")
            if (Modifier.isPrivate(visibility)) throw IllegalArgumentException("Illegal modifier \"private\"")

            if (isAbstract) append("abstract ")
            if (Modifier.isStatic(visibility)) println("WARNING: Redundant modifier \"static\"")
            if (Modifier.isFinal(visibility)) {
                if (isAbstract)
                    throw IllegalArgumentException("Illegal modifier \"final\"")
                else
                    append("final ")
            }
            if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
            if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
            if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
            if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
            if (Modifier.isStrict(visibility)) throw IllegalArgumentException("Illegal modifier \"strictfp\"")
            if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

            toString()
        }

        val target = JavaClass(fileName, packageName, moduleName, superClass, visibility, v, kind)
        init.invoke(target)
        this@JavaInterface.body.add(target)

        return target
    }

    fun javaInterface(
        fileName: String,
        packageName: String,
        moduleName: String,
        visibility: Int = 0,
        kind: String = KIND_MAIN,
        init: JavaInterface.() -> Unit
    ): JavaInterface {
        val v = StringBuilder().run {
            if (Modifier.isPublic(visibility)) append("public ")
            if (Modifier.isProtected(visibility)) throw IllegalArgumentException("Illegal modifier \"protected\"")
            if (Modifier.isPrivate(visibility)) throw IllegalArgumentException("Illegal modifier \"private\"")

            if (Modifier.isAbstract(visibility)) println("WARNING: Redundant modifier \"abstract\"")
            if (Modifier.isStatic(visibility)) println("WARNING: Redundant modifier \"static\"")
            if (Modifier.isFinal(visibility)) throw IllegalArgumentException("Illegal modifier \"final\"")
            if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
            if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
            if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
            if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
            if (Modifier.isStrict(visibility)) throw IllegalArgumentException("Illegal modifier \"strictfp\"")
            // if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

            toString()
        }

        val target = JavaInterface(fileName, packageName, moduleName, visibility, v, kind)
        init.invoke(target)
        this@JavaInterface.body.add(target)

        return target
    }

    fun IType.method(
        name: String,
        documentation: String,
        vararg parameters: JavaParameter,
        visibility: Int = 0,
        body: String? = null,
        category: String = "",
        returnDoc: String = "",
        since: String = "",
        throws: Array<out String>? = null,
        see: Array<out String>? = null,
        typeParameters: Array<out JavaTypeParameter>? = null,
        annotations: List<Annotation>? = null,
        preserveOrder: Boolean = true
    ) {
        val v = StringBuilder().run {
            val isStatic = Modifier.isStatic(visibility)

            if (Modifier.isPublic(visibility)) println("WARNING: Redundant modifier \"public\"")
            if (Modifier.isProtected(visibility)) throw IllegalArgumentException("Illegal modifier \"protected\"")
            if (Modifier.isPrivate(visibility)) {
                if (isStatic)
                    append("private ")
                else
                    throw IllegalArgumentException("Illegal modifier \"private\"")
            }

            if (Modifier.isAbstract(visibility)) {
                if (isStatic)
                    throw IllegalArgumentException("Illegal modifier \"abstract\"")
                else
                    println("WARNING: Redundant modifier \"abstract\"")
            }
            if (isStatic) append("static ")
            if (Modifier.isFinal(visibility)) {
                if (isStatic)
                    println("WARNING: Redundant modifier \"final\"")
                else
                    throw IllegalArgumentException("Illegal modifier \"final\"")
            }
            if (Modifier.isTransient(visibility)) throw IllegalArgumentException("Illegal modifier \"transient\"")
            if (Modifier.isVolatile(visibility)) throw IllegalArgumentException("Illegal modifier \"volatile\"")
            if (body != null && !isStatic) append("default ")
            if (Modifier.isSynchronized(visibility)) throw IllegalArgumentException("Illegal modifier \"synchronized\"")
            if (Modifier.isNative(visibility)) throw IllegalArgumentException("Illegal modifier \"native\"")
            if (Modifier.isStrict(visibility)) {
                if (isStatic)
                    append("strictfp ")
                else
                    throw IllegalArgumentException("Illegal modifier \"strictfp\"")
            }
            if (Modifier.isInterface(visibility)) throw IllegalArgumentException("Illegal modifier \"interface\"")

            toString()
        }

        addImport(this)
        parameters.forEach { addImport(it.type) }

        val method = JavaMethod(this, name, documentation, parameters, v, body, category, returnDoc, since, throws, see, typeParameters, annotations, preserveOrder)
        this@JavaInterface.body.add(method)
    }

    override fun getWeight() = WEIGHT_SUBTYPE

    override fun PrintWriter.printTypeDeclaration(indent: String) {
        print(visibility)
        print("interface ")
        print(fileName)

        if (typeParameters.isNotEmpty()) {
            print("<")
            print(StringJoiner(", ").apply {
                typeParameters.forEach { add(it.toString()) }
            })
            print(">")
        }

        if (interfaces.isNotEmpty()) {
            print(" extends ")
            print(StringJoiner(", ").apply {
                interfaces.forEach { add(it.toString()) }
            })
        }
    }

}

class JavaField internal constructor(
    val type: IType,
    override val name: String,
    val documentation: String,
    val visibility: String,
    val value: String,
    override var category: String,
    val since: String,
    val see: Array<out String>?,
    val annotations: List<Annotation>?
): Member {

    override fun getWeight() = WEIGHT_FIELD

    override fun PrintWriter.printMember(indent: String): Boolean {
        if (documentation.isNotEmpty()) println(documentation.toJavaDoc(indent = indent, see = see, since = since))

        val annotations = this@JavaField.annotations
        if (annotations != null) print(printAnnotations(indent = indent, annotations = annotations))

        print(indent)
        print(visibility)
        print(type.toString())
        print(" ")
        print(name)

        if (value.isNotEmpty()) {
            print(" = ")
            print(value)
        }

        println(";")

        return false
    }

}

class JavaConstructor internal constructor(
    type: IType,
    documentation: String,
    parameters: Array<out JavaParameter>,
    visibility: String,
    body: String?,
    category: String,
    since: String,
    throws: Array<out String>?,
    see: Array<out String>?,
    typeParameters: Array<out JavaTypeParameter>?,
    annotations: List<Annotation>?,
    preserveOrder: Boolean
): JavaMethod(type, type.simpleName, documentation, parameters, visibility, body, category, "", since, throws, see, typeParameters, annotations, preserveOrder) {

    override fun getWeight() = WEIGHT_CONSTRUCTOR

    override fun PrintWriter.printDeclaration() {
        print(visibility)
        print(name)
    }

}

open class JavaMethod internal constructor(
    val type: IType,
    override val name: String,
    val documentation: String,
    val parameters: Array<out JavaParameter>,
    val visibility: String,
    val body: String?,
    override var category: String,
    val returnDoc: String,
    val since: String,
    val throws: Array<out String>?,
    val see: Array<out String>?,
    val typeParameters: Array<out JavaTypeParameter>?,
    val annotations: List<Annotation>?,
    val preserveOrder: Boolean
): Member {

    override fun compareTo(other: Member): Int {
        var cmp = super.compareTo(other)

        if (cmp != 0) return cmp

        if (other is JavaMethod && !preserveOrder) {
            val maxParams = Math.max(parameters.size, other.parameters.size) - 1

            for (i in 0..maxParams) {
                if (parameters.size <= i && other.parameters.size > i)
                    return -1
                else if (other.parameters.size <= i)
                    return 1

                cmp = parameters[i].type.simpleName.compareTo(other.parameters[i].type.simpleName)

                if (cmp != 0) return cmp
            }
        }

        return 1
    }

    override fun getWeight() = WEIGHT_METHOD

    open fun PrintWriter.printDeclaration() {
        print(visibility)


        if (typeParameters != null && typeParameters.isNotEmpty()) {
            print("<")
            print(StringJoiner(", ").apply {
                typeParameters.forEach { add(it.toString()) }
            })
            print("> ")
        }

        print("$type ")
        print(name)
    }

    override fun PrintWriter.printMember(indent: String): Boolean {
        println(toJavaDoc(indent = indent))

        val annotations = this@JavaMethod.annotations
        if (annotations != null) println(printAnnotations(indent = indent, annotations = annotations))

        print(indent)
        printDeclaration()
        print("(")

        if (parameters.isNotEmpty()) {
            print(StringJoiner(", ").apply {
                parameters.forEach {
                    add(StringJoiner(" ").run {
                        if (it.annotations != null) add(printAnnotations(annotations = it.annotations, separator = " "))
                        add(it.type.toString())
                        add(it.name)

                        toString()
                    })
                }
            })
        }

        print(")")

        if (body == null)
            println(";")
        else {
            print(" {")

            if (body.isNotEmpty()) {
                var body: String = body

                while (body.startsWith(LN)) body = body.removePrefix(LN)
                while (body.endsWith(LN)) body = body.removeSuffix(LN)

                println()
                body.lineSequence().forEach {
                    print(indent + INDENT)
                    println(it)
                }

                print(indent)
            }

            println("}")
        }

        println()

        return true
    }

}

fun IType.PARAM(
    name: String,
    documentation: String,
    annotations: List<Annotation>? = null
) = JavaParameter(this, name, documentation, annotations = annotations)

class JavaParameter internal constructor(
    val type: IType,
    val name: String,
    val documentation: String,
    val annotations: List<Annotation>? = null
)

class JavaTypeParameter(
    val type: IType,
    val documentation: String
) {

    override fun toString() = type.toString()

}

internal fun printAnnotations(indent: String = "", annotations: Collection<Annotation>, separator: String = LN) =
    StringJoiner(separator).run {
        annotations.forEach { add("$indent@$it${if (it.parameters.isNotEmpty()) "(${it.parameters})" else ""}") }
        toString()
    }