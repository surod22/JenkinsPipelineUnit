package com.lesfurets.jenkins.unit.global.lib

import static com.lesfurets.jenkins.unit.MethodSignature.method

import java.lang.reflect.Method
import java.nio.file.Files

import org.apache.commons.io.FilenameUtils
import org.codehaus.groovy.runtime.DefaultGroovyMethods

import com.lesfurets.jenkins.unit.PipelineTestHelper

/**
 * Loads libraries to groovy class loader
 */
class LibraryLoader {

    private static Method SCRIPT_SET_BINDING = Script.getMethod('setBinding', Binding.class)

    private final GroovyClassLoader groovyClassLoader

    private final Map<String, LibraryConfiguration> libraryDescriptions

    protected final Map<String, LibraryRecord> libRecords = new HashMap<>()

    LibraryLoader(GroovyClassLoader groovyClassLoader, Map<String, LibraryConfiguration> libraryDescriptions) {
        this.groovyClassLoader = groovyClassLoader
        this.libraryDescriptions = libraryDescriptions
    }

    static String getLibraryId(LibraryConfiguration lib, String version = null) {
        return "$lib.name@${version ?: lib.defaultVersion}"
    }

    /**
     * Loads all implicit library configurations
     */
    void loadImplicitLibraries() {
        libraryDescriptions.values().stream()
                .filter { it.implicit }
                .filter { !libRecords.containsKey(getLibraryId(it)) }
                .forEach {
            doLoadLibrary(it)
        }
    }

    /**
     * Load library described by expression if it corresponds to a known library configuration
     * @param expression
     * @throws Exception
     */
    void loadLibrary(String expression) throws Exception {
        def lib = parse(expression)
        def libName = lib[0]
        def version = lib[1]
        def library = libraryDescriptions.get(libName)
        if (!library) {
            throw new Exception("Library description '$libName' not found")
        }
        if (!matches(libName, version, library)) {
            throw new Exception("Library '$expression' does not match description $library")
        }
        if (!libRecords.containsKey(getLibraryId(library, version))) {
            doLoadLibrary(library, version)
        }
    }

    /**
     * Sets global variables defined in loaded libraries on the binding
     * @param binding
     */
    void setGlobalVars(Binding binding, PipelineTestHelper helper) {
        libRecords.values().stream()
                .flatMap { it.definedGlobalVars.entrySet().stream() }
                .forEach { e ->
            if (e.value instanceof Script) {
                Script script = Script.cast(e.value)
                // invoke setBinding from method to avoid interception
                SCRIPT_SET_BINDING.invoke(script, binding)
                script.metaClass.getMethods().findAll { it.name == 'call' }.forEach { m ->
                    helper.registerAllowedMethod(method(e.value.class.name, m.getNativeParameterTypes()),
                                    { args -> m.doMethodInvoke(e.value, args) })
                }
            } else {
                binding.setVariable(e.key, e.value)
            }
        }
    }

    /**
     * Loads library to groovy class loader.
     * @param library library configuration.
     * @param version version to load, if null loads the default version defined in configuration.
     * @throws Exception
     */
    private void doLoadLibrary(LibraryConfiguration library, String version = null) throws Exception {
        println "Loading shared library ${library.name} with version ${version ?: library.defaultVersion}"
        try {
            def urls = library.retriever.retrieve(library.name, version ?: library.defaultVersion, library.targetPath)
            def record = new LibraryRecord(library, version ?: library.defaultVersion, urls.path)
            libRecords.put(record.getIdentifier(), record)
            def globalVars = [:]
            urls.forEach { url ->
                def file = new File(url.toURI())

                def srcPath = file.toPath().resolve('src')
                def varsPath = file.toPath().resolve('vars')
                groovyClassLoader.addURL(srcPath.toUri().toURL())
                groovyClassLoader.addURL(varsPath.toUri().toURL())

                if (varsPath.toFile().exists()) {
                    Files.list(varsPath)
                                    .map { it.toFile() }
                                    .filter { it.name.endsWith('.groovy') }
                                    .map { FilenameUtils.getBaseName(it.name) }
                                    .filter { !globalVars.containsValue(it) }
                                    .forEach {
                        def clazz = groovyClassLoader.loadClass(it)
                        // instanciate by invokeConstructor to avoid interception
                        Object var = DefaultGroovyMethods.newInstance(clazz)
                        globalVars.put(it, var)
                    }
                }
            }
            record.definedGlobalVars = globalVars
        } catch (Exception e) {
            throw new LibraryLoadingException(e, library, version)
        }
    }

    private static String[] parse(String identifier) {
        identifier.split('@')
        int at = identifier.indexOf('@')
        if (at == -1) {
            return [identifier, null] // pick up defaultVersion
        } else {
            return [identifier.substring(0, at), identifier.substring(at + 1)]
        }
    }

    private static boolean matches(String libName, String version, LibraryConfiguration libraryDescription) {
        if (libraryDescription.name == libName) {
            if (version == null) {
                return true
            }
            if (libraryDescription.allowOverride || libraryDescription.defaultVersion == version) {
                return true
            }
        }
        return false
    }

    static class LibraryLoadingException extends Exception {

        LibraryLoadingException(Throwable cause, LibraryConfiguration configuration, String version) {
            super("Error on loading library ${LibraryLoader.getLibraryId(configuration, version)} : ${cause.message}", cause)
        }
    }

}
