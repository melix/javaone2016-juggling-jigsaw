import groovy.transform.CompileStatic

@CompileStatic
class ApiExtension {
    final Set<String> exports = []
    String moduleName

    void exports(String pkg) { exports << pkg }

}
