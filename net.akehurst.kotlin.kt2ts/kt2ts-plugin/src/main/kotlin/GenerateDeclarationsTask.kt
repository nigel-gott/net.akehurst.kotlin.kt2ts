/**
 * Copyright (C) 2018 Dr. David H. Akehurst (http://dr.david.h.akehurst.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.akehurst.kotlin.kt2ts.plugin.gradle

import com.github.jknack.handlebars.Context
import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.context.MapValueResolver
import com.github.jknack.handlebars.io.ClassPathTemplateLoader
import com.github.jknack.handlebars.io.FileTemplateLoader
import io.github.classgraph.*
import kotlinx.metadata.KmClass
import kotlinx.metadata.jvm.KmModule
import kotlinx.metadata.jvm.KotlinClassHeader
import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.jvm.KotlinModuleMetadata
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.slf4j.LoggerFactory
import java.io.*
import java.lang.RuntimeException
import java.net.MalformedURLException
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import kotlin.Comparator
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.streams.toList

class KtMetaModule(
        val metadata: KmModule
) {
    val classes = mutableListOf<KmClass>()
}

open class GenerateDeclarationsTask : DefaultTask() {

    companion object {
        val NAME = "generateTypescriptDefinitionFile"
        private val LOGGER = LoggerFactory.getLogger(GenerateDeclarationsTask::class.java)
    }

    @get:Input
    var overwrite = project.objects.property(Boolean::class.java)

    @get:Input
    var localOnly = project.objects.property(Boolean::class.java)

    @get:Input
    var jvmName = project.objects.property(String::class.java)

    @get:Input
    var templateFileName = project.objects.property(String::class.java)

    @get:InputDirectory
    @get:Optional
    var templateDir = project.objects.directoryProperty()

    @get:OutputFile
    var outputFile = project.objects.fileProperty()

    @get:Input
    var classPatterns = project.objects.listProperty(String::class.java)

    @get:Input
    @get:Optional
    var typeMapping = project.objects.mapProperty(String::class.java, String::class.java)

    init {
        this.group = "generate"
        this.description = "Generate typescript declaration file (*.d.ts) from kotlin classes"

        this.overwrite.set(false)
        this.localOnly.set(true)
        this.jvmName.set("jvm")
        //this.templateDir.set(null as Directory?)
        this.templateFileName.set("template.hbs")
        this.typeMapping.set(mapOf(
                "kotlin.Any" to "any",
                "kotlin.String" to "string",
                "kotlin.Int" to "number",
                "kotlin.Float" to "number",
                "kotlin.Double" to "number",
                "kotlin.Boolean" to "boolean"
        ))
    }

    @TaskAction
    internal fun exec() {
        LOGGER.info("Executing $NAME")

        val model = this.createModel(this.classPatterns.get())
        //val model2 = this.createModel2(this.classPatterns.get())
        val td = this.templateDir.orNull?.asFile
        val of = this.outputFile.get().asFile
        this.generate(model, td, of)

    }

    private fun generate(model: Map<String, Any>, templateDir: File?, outputFile: File) {
        LOGGER.info("Generating ", outputFile)
        // final DefaultMustacheFactory mf = new DefaultMustacheFactory();
        if (outputFile.exists() && overwrite.get().not()) {
            LOGGER.info("Skipping $outputFile as it exists, set overwrite=true to overwrite the file")
        } else {
            if(outputFile.exists()) {
                LOGGER.info("Overwriting existing $outputFile, set overwrite=false to keep the orginal")
            }
            val ftl = when {
                null != templateDir -> {
                    LOGGER.info("Using template directory $templateDir")
                    FileTemplateLoader(templateDir, "")
                }
                else -> {
                    LOGGER.info("Using default template")
                    ClassPathTemplateLoader("/handlebars")
                }
            }
            val hb = Handlebars(ftl)

            try {
                LOGGER.info("Compiling template ${templateFileName.get()}")
                val tn = templateFileName.get()
                val tmn = if (tn.endsWith(".hbs")) tn.removeSuffix(".hbs") else tn
                val t = hb.compile(tmn)

                LOGGER.info("Generating output")
                val writer = FileWriter(outputFile)

                val ctx = Context.newBuilder(model).resolver(MapValueResolver.INSTANCE).build()

                t.apply(ctx, writer)
                writer.close()
            } catch (e: Exception) {
                LOGGER.error("Unable to generate", e)
            }
        }
    }
/*
    private fun createModel2(classPatterns: List<String>): Map<String, Any> {
        LOGGER.info("Scanning classpath")
        LOGGER.info("Including:")
        classPatterns.forEach {
            LOGGER.info("  ${it}")
        }

        val modules = fetchModules()
        modules.forEach { moduleName, ktModule ->
            println(moduleName)
            ktModule.classes.forEach { cls ->
                println("  $cls")
            }
        }

        val model = mutableMapOf<String, Any>()

        return model
    }

    private fun fetchModules(): Map<String, KtMetaModule> {
        val urls = mutableListOf<URL>()
        val moduleNames = mutableListOf<String>()
        val localBuild = project.buildDir.resolve("classes/kotlin/metadata/main")
        val localUrl = localBuild.absoluteFile.toURI().toURL() //URL("file://"+localBuild.absoluteFile+"/")
        urls.add(localUrl)
        moduleNames.add(project.name)

        val configurationName = "metadataDefault"
        val c = this.project.configurations.findByName(configurationName) ?: throw RuntimeException("Cannot find $configurationName configuration")
        c.isCanBeResolved = true
        for (file in c.resolve()) {
            try {
                LOGGER.debug("Adding url for $file")
                urls.add(file.toURI().toURL())
                moduleNames.add(project.name)
            } catch (e: MalformedURLException) {
                LOGGER.error("Unable to create url for $file", e)
            }

        }
        moduleNames.addAll(c.allDependencies.map { it.name })
        val cl = URLClassLoader(urls.toTypedArray(), Thread.currentThread().contextClassLoader)

        val modules = moduleNames.associate { moduleName ->
            val moduleFileBytes = cl.getResourceAsStream("META-INF/$moduleName.kotlin_module").readAllBytes()
            val module = fetchModule(moduleName, moduleFileBytes)
            classPatterns.get().forEach {
                val pathPat = it.replace(".", "/")
                LOGGER.info("Finding class metadata in $pathPat")
                cl.getResourceAsStream("$pathPat").use { dirStrm ->
                    val files = getResourceFiles(dirStrm)
                    files.forEach {fileName ->
                       val clsMetaBytes =  cl.getResourceAsStream("$pathPat/fileName").readAllBytes()
                    }

                }
            }
            Pair(moduleName, module)
        }
        return modules
    }

    private fun fetchModule(moduleName: String, moduleFileBytes: ByteArray): KtMetaModule {
        val metadata = KotlinModuleMetadata.read(moduleFileBytes) ?: throw RuntimeException("Module metadata not found for $moduleName")
        val kmModule = metadata.toKmModule()
        return KtMetaModule(kmModule)
    }
*/
    private fun getResourceFiles(dirStrm: InputStream): List<String> {
        val filenames = BufferedReader(InputStreamReader(dirStrm)).lines().toList()
        return filenames;
    }


    private fun createModel(classPatterns: List<String>): Map<String, Any> {
        LOGGER.info("Scanning classpath")
        LOGGER.info("Including:")
        classPatterns.forEach {
            LOGGER.info("  ${it}")
        }
        val clToGenerate = this.createClassLoader(false)
        val clForAll = this.createClassLoader(true)
        val scan = ClassGraph()//
                .ignoreParentClassLoaders()
                .overrideClassLoaders(clToGenerate)//
                .enableClassInfo()//
                //.verbose()//
                //.enableExternalClasses() //
                //.enableAnnotationInfo() //
                //.enableSystemPackages()//
                .whitelistPackages(*classPatterns.toTypedArray())//
                .scan()
        val classInfo = scan.allClasses
        LOGGER.info("Building Datatype model for ${classInfo.size} types")

        val comparator = Comparator { c1: ClassInfo, c2: ClassInfo ->
            //println("$c1 - $c2")
            when {
                c1 == c2 -> 0
                c1.getSuperclasses().contains(c2) -> 1
                c2.getSuperclasses().contains(c1) -> -1
                else -> 0
            }
        }
        val filtered = classInfo.filter {
            it.name.contains("$").not()
        }

        val sorted = filtered.sortedWith(comparator)
        val namespaceGroups = sorted.groupBy { it.packageName }

        val model = mutableMapOf<String, Any>()
        val namespaces = mutableListOf<Map<String, Any>>()
        model["namespace"] = namespaces
        namespaceGroups.forEach { (nsn, classes) ->
            val namespace = mutableMapOf<String, Any>()
            namespaces.add(namespace)
            val datatypes = mutableListOf<Map<String, Any>>()
            namespace["name"] = nsn
            namespace["datatype"] = datatypes
            for (cls in classes) {
                val kclass: KClass<*> = clForAll.loadClass(cls.name).kotlin
                val pkgName = kclass.qualifiedName!!.substringBeforeLast('.')
//               println("KClass: ${kclass.simpleName} ${kclass.isData}")
                //               kclass.memberProperties.forEach {
                //                   println("    ${it.name} : ${it.returnType}")
                //               }

                LOGGER.debug("Adding Class " + cls.getName())
                val dtModel = mutableMapOf<String, Any>()
                datatypes.add(dtModel)
                dtModel["name"] = kclass.simpleName!!
                dtModel["fullName"] = kclass.qualifiedName!!
                dtModel["isInterface"] = cls.isInterface
                dtModel["isAbstract"] = kclass.isAbstract
                dtModel["isEnum"] = cls.isEnum

                val extends_ = ArrayList<Map<String, Any>>()
                dtModel["extends"] = extends_
                if (null != cls.getSuperclass()) {
                    extends_.add(this.createPropertyType(cls.getSuperclass(), ArrayList<TypeArgument>(), false))
                }
                val implements_ = ArrayList<Map<String, Any>>()
                dtModel["implements"] = implements_
                for (i in cls.getInterfaces()) {
                    //if (i.hasAnnotation(Datatype::class.java!!.getName())) {
                    implements_.add(this.createPropertyType(i, ArrayList<TypeArgument>(), false))
                    //}
                }

                val property = mutableListOf<Map<String, Any>>()
                dtModel["property"] = property
                kclass.memberProperties.forEach {
                    //    println("    ${it.name} : ${it.returnType}")
                    val prop = mutableMapOf<String, Any>()
                    prop["name"] = it.name
                    prop["type"] = this.createPropertyType2(it.returnType, pkgName)
                    property.add(prop)
                }

                /*
                for (mi in cls.getMethodInfo()) {

                    if (mi.parameterInfo.size === 0 && mi.getName().startsWith("get")) {
                        val meth = HashMap<String, Any>()
                        meth["name"] = this.createMemberName(mi.getName())
                        val propertyTypeSig = mi.getTypeSignatureOrTypeDescriptor().getResultType()
                        meth["type"] = this.createPropertyType(propertyTypeSig, true)
                        property.add(meth)
                    }
                }
                 */
            }
        }
        return model
    }

    private fun createClassLoader(forAll:Boolean): URLClassLoader {
        val urls = mutableListOf<URL>()
        val localBuild = project.buildDir.resolve("classes/kotlin/${jvmName.get()}/main")//project.tasks.getByName("jvm8MainClasses").outputs.files
        val localUrl = localBuild.absoluteFile.toURI().toURL() //URL("file://"+localBuild.absoluteFile+"/")
        urls.add(localUrl)
        if (forAll || localOnly.get().not()) {
            val configurationName = jvmName.get() + "RuntimeClasspath"
            val c = this.project.configurations.findByName(configurationName) ?: throw RuntimeException("Cannot find $configurationName configuration")
            c.isCanBeResolved = true
            c.resolvedConfiguration.resolvedArtifacts.forEach { dep ->
                val file = dep.file
                try {
                    LOGGER.debug("Adding url for $file")
                    urls.add(file.toURI().toURL())
                } catch (e: MalformedURLException) {
                    LOGGER.error("Unable to create url for $file", e)
                }

            }
            /*
            for (file in c.resolve()) {
                try {
                    LOGGER.debug("Adding url for $file")
                    urls.add(file.toURI().toURL())
                } catch (e: MalformedURLException) {
                    LOGGER.error("Unable to create url for $file", e)
                }

            }
             */
        }
        return URLClassLoader(urls.toTypedArray(), Thread.currentThread().contextClassLoader)
    }

    private fun createMemberName(methodName: String): String {
        return methodName.substring(3, 4).toLowerCase() + methodName.substring(4)
    }

    private fun createPropertyType(ci: ClassInfo, tArgs: List<TypeArgument>, isRef: Boolean): MutableMap<String, Any> {
        val mType = mutableMapOf<String, Any>()
        val fullName = ci.getName()
        mType["fullName"] = fullName
        mType["name"] = fullName.substring(fullName.lastIndexOf('.') + 1)
        if (ci.implementsInterface("java.util.Collection")) {
            mType["isCollection"] = true
            mType["isOrdered"] = "java.util.List" == ci.getName()

            val et = this.createPropertyType(tArgs[0].getTypeSignature(), false) // what is isRef here!
            LOGGER.info("Collection with elementType " + tArgs[0].getTypeSignature())
            mType["elementType"] = et

        } else if (ci.isEnum()) {
            mType["isEnum"] = true
        }
        mType["isReference"] = isRef
        return mType
    }

    private fun createPropertyType(typeSig: TypeSignature, isRef: Boolean): Map<String, Any> {
        var mType: MutableMap<String, Any> = mutableMapOf()
        if (typeSig is BaseTypeSignature) {
            val bts = typeSig as BaseTypeSignature
            mType["name"] = bts.getTypeStr()
            mType["fullName"] = bts.getTypeStr()
        } else if (typeSig is ArrayTypeSignature) {
            val rts = typeSig as ArrayTypeSignature
            mType["name"] = "array"
            mType["fullName"] = "array"
            mType["isCollection"] = true
            mType["elementType"] = this.createPropertyType(rts.getElementTypeSignature(), isRef)
        } else if (typeSig is ClassRefTypeSignature) {
            val cts = typeSig as ClassRefTypeSignature
            if (null == cts.getClassInfo()) {
                val fullName = cts.getFullyQualifiedClassName()
                mType["fullName"] = fullName
                mType["name"] = fullName.substring(fullName.lastIndexOf('.') + 1)
                LOGGER.error("Unable to find class info for " + cts.getFullyQualifiedClassName())
            } else {
                mType = this.createPropertyType(cts.getClassInfo(), cts.getTypeArguments(), isRef)
            }
        } else {
        }

        val mappedName = this.typeMapping.get()[mType["fullName"]]
        if (null == mappedName) {
            return mType
        } else {
            LOGGER.debug("Mapping " + mType["name"] + " to " + mappedName)
            mType["name"] = mappedName
            return mType
        }

    }

    private fun createPropertyType2(ktype: KType, owningTypePackageName: String): Map<String, Any> {
        var mType: MutableMap<String, Any> = mutableMapOf()
        val kclass = ktype.classifier
        //if (null==kclass) {
        //    mType["name"] = "any"
        //    mType["qualifiedName"] = "any"
        //} else {
            when (kclass) {
                is KClass<*> -> {
                    val pkgName = kclass.qualifiedName!!.substringBeforeLast('.')
                    mType["name"] = kclass.simpleName!!
                    mType["qualifiedName"] = kclass.qualifiedName!!
                    mType["isCollection"] = kclass.isSubclassOf(Collection::class)
                    mType["isSamePackage"] = pkgName == owningTypePackageName
                    if (kclass.isSubclassOf(Collection::class)) {
                        val elementTypes = ktype.arguments.map {
                            this.createPropertyType2(it.type!!, owningTypePackageName)
                        }
                        mType["elementType"] = elementTypes
                    }
                }
                else -> throw RuntimeException("cannot handle property type ${ktype::class} ${ktype}")
            }
        //}
        /*
        if (typeSig is BaseTypeSignature) {
            val bts = typeSig as BaseTypeSignature
            mType["name"] = bts.getTypeStr()
            mType["fullName"] = bts.getTypeStr()
        } else if (typeSig is ArrayTypeSignature) {
            val rts = typeSig as ArrayTypeSignature
            mType["name"] = "array"
            mType["fullName"] = "array"
            mType["isCollection"] = true
            mType["elementType"] = this.createPropertyType(rts.getElementTypeSignature(), isRef)
        } else if (typeSig is ClassRefTypeSignature) {
            val cts = typeSig as ClassRefTypeSignature
            if (null == cts.getClassInfo()) {
                val fullName = cts.getFullyQualifiedClassName()
                mType["fullName"] = fullName
                mType["name"] = fullName.substring(fullName.lastIndexOf('.') + 1)
                LOGGER.error("Unable to find class info for " + cts.getFullyQualifiedClassName())
            } else {
                mType = this.createPropertyType(cts.getClassInfo(), cts.getTypeArguments(), isRef)
            }
        } else {
        }
*/
        val mappedName = this.typeMapping.get()[mType["qualifiedName"]]
        if (null == mappedName) {
            return mType
        } else {
            LOGGER.debug("Mapping " + mType["name"] + " to " + mappedName)
            mType["name"] = mappedName
            mType["qualifiedName"] = mappedName
            return mType
        }

    }

}