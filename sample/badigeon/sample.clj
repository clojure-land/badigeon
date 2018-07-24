(ns badigeon.sample
  (:require [badigeon.clean :as clean]
            [badigeon.javac :as javac]
            [badigeon.compile :as compile]
            [badigeon.jar :as jar]
            [badigeon.install :as install]
            [badigeon.prompt :as prompt]
            [badigeon.sign :as sign]
            [badigeon.deploy :as deploy]
            [badigeon.bundle :as bundle]
            [badigeon.zip :as zip]
            ;; Requires a JDK 9+
            [badigeon.jlink :as jlink]
            [clojure.tools.deps.alpha.reader :as deps-reader]
            [clojure.tools.deps.alpha.util.maven :as maven]))

(defn -main []
  ;; Delete the target directory
  (clean/clean "target"
               {;; By default, Badigeon does not allow deleting forlders outside the target directory,
                ;; unless :allow-outside-target? is true
                :allow-outside-target? false})

  ;; Compile java sources under the src-java directory
  (javac/javac "src-java" {;; Emit class files to the target/classes directory
                           :compile-path "target/classes"
                           ;; Additional options used by the javac command
                           :compiler-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]})

  ;; AOT compiles the badigeon.main namespace. Badigeon AOT compiles Clojure sources in a fresh classloader, using the clojure.core/compile function. As a consequence, all the namespaces and their dependencies always get recompiled, unlike with the clojure.core/compile function. Beware of side effects triggered while loading the compiled namespaces.
  (compile/compile '[badigeon.main]
                   {;; Emit class files to the target/classes directory
                    :compile-path "target/classes"
                    ;; Compiler options used by the clojure.core/compile function
                    :compiler-options {:disable-locals-clearing false
                                       :elide-meta [:doc :file :line :added]
                                       :direct-linking true}})

  ;; Package the project into a jar file
  (jar/jar 'badigeon/badigeon {:mvn/version "0.0.1-SNAPSHOT"}
           {;; The jar file produced.
            :out-path "target/badigeon-0.0.1-SNAPSHOT.jar"
            ;; Adds a \"Main\" entry to the jar manifest with the value \"badigeon.main\"
            :main 'badigeon.main
            ;; Additional key/value pairs to add to the jar manifest. If a value is a collection, a manifest section is built for it.
            :manifest {"Project-awesome-level" "super-great"
                       :my-section-1 [["MyKey1" "MyValue1"] ["MyKey2" "MyValue2"]]
                       :my-section-2 {"MyKey3" "MyValue3" "MyKey4" "MyValue4"}}
            ;; By default Badigeon add entries for all files in the directory listed in the :paths section of the deps.edn file. This can be overridden here.
            :paths ["src" "target/classes"]
            ;; The dependencies to be added to the \"dependencies\" section of the pom.xml file. When not specified, defaults to the :deps entry of the deps.edn file, without merging the user-level and system-level deps.edn files
            :deps '{org.clojure/clojure {:mvn/version "1.9.0"}}
            ;; The repositories to be added to the \"repositories\" section of the pom.xml file. When not specified, default to nil - even if the deps.edn files contains a :mvn/repos entry.
            :mvn/repos '{"clojars" {:url "https://repo.clojars.org/"}}
            ;; A predicate used to excludes files from beeing added to the jar. The predicate is a function of two parameters: The path of the directory beeing visited (among the :paths of the project) and the path of the file beeing visited under this directory.
            :exclusion-predicate badigeon.jar/default-exclusion-predicate
            ;; A function to add files to the jar that would otherwise not have been added to it. The function must take two parameters: the path of the root directory of the project and the file being visited under this directory. When the function returns a falsy value, the file is not added to the jar. Otherwise the function must return a string which represents the path within the jar where the file is copied. 
            :inclusion-path (partial badigeon.jar/default-inclusion-path "badigeon" "badigeon")
            ;; By default git and local dependencies are not allowed. Set allow-all-dependencies? to true to allow them 
            :allow-all-dependencies? true})

  ;; Install the previously created jar file into the local maven repository.
  (install/install 'badigeon/badigeon {:mvn/version "0.0.1-SNAPSHOT"}
                   ;; The jar file to be installed
                   "target/badigeon-0.0.1-SNAPSHOT.jar"
                   ;; The pom.xml file to be installed. This file is generated when creating the jar with the badigeon.jar/jar function.
                   "pom.xml"
                   {;; The local repository where the jar should be installed.
                    :local-repo (str (System/getProperty "user.home") "/.m2/repository")})

  ;; Deploy the previously created jar file to a remote repository.
  (let [;; Artifacts are maps with a required :file-path key and an optional :extension key
        artifacts [{:file-path "target/badigeon-0.0.1-SNAPSHOT.jar" :extension "jar"}
                   {:file-path "pom.xml" :extension "pom"}]
        ;; Artifacts must be signed when deploying non-snapshot versions of artifacts.
        artifacts (badigeon.sign/sign artifacts {;; The gpg command can be customized
                                                 :command "gpg"
                                                 ;; The gpg key used for signing. Defaults to the first private key found in your keyring. 
                                                 :gpg-key "root@eruditorum.org"})
        ;; Prompt for a password using the process standard input and without echoing.
        password (badigeon.prompt/prompt-password "Password: ")]
    (badigeon.deploy/deploy
     'badigeon/badigeon {:mvn/version "0.0.1-SNAPSHOT"}
     artifacts
     {;; :id is used to match the repository in the ~/.m2/settings.xml for credentials when no credentials are explicitly provided.
      :id "clojars"
      ;; The URL of the repository to deploy to.
      :url "https://repo.clojars.org/"}
     {;; The credentials used when authenticating to the remote repository. When none is provided, default to reading the credentials from ~/.m2/settings.xml
      :credentials {:username "ewen" :password password
                    :private-key "/path/to/private-key" :passphrase "passphrase"}
      ;; When allow-unsigned? is false, artifacts must be signed when deploying non-snapshot versions of artifacts. Default to false.
      :allow-unsigned? true}))

  ;; Make a standalone bundle of the application.
  (let [;; Automatically compute the bundle directory name based on the application name and version.
        out-path (badigeon.bundle/make-out-path 'badigeon/badigeon "0.0.1-SNAPSHOT")]
    (badigeon.bundle/bundle out-path
                            {;; A map with the same format than deps.edn. :deps-map is used to resolve the project dependencies.
                             :deps-map (deps-reader/slurp-deps "deps.edn")
                             ;; The dependencies to be excluded from the produced bundle.
                             :excluded-libs #{'org.clojure/clojure}
                             ;; Set to true to allow local dependencies and snpashot versions of maven dependencies.
                             :allow-unstable-deps? true
                             ;; The path of the folder where dependencies are copied, relative to the output folder.
                             :libs-path "lib"})
    ;; Extract native dependencies (.so, .dylib, .dll, .a, .lib files) from jar dependencies.
    (bundle/extract-native-dependencies out-path
                                        {;; A map with the same format than deps.edn. :deps-map is used to resolve the project dependencies.
                                         :deps-map (deps-reader/slurp-deps "deps.edn")
                                         ;; Set to true to allow local dependencies and snpashot versions of maven dependencies.
                                         :allow-unstable-deps? true
                                         ;; The directory where native dependencies are copied.
                                         :native-path "lib"
                                         ;; The paths where native dependencies should be searched. The native-prefix is excluded from the output path of the native dependency.
                                         :native-prefixes {'org.lwjgl.lwjgl/lwjgl-platform ""}})

    ;; Requires a JDK9+
    ;; Embeds a custom JRE runtime into the bundle.
    (jlink/jlink out-path {;; The folder where the custom JRE is output, relative to the out-path.
                           :jlink-path "runtime"
                           ;; The path where the java module are searched for.
                           :module-path (str (System/getProperty "java.home") "/jmods")
                           ;; The modules to be used when creating the custom JRE
                           :modules ["java.base"]
                           ;; The options of the jlink command
                           :jlink-options ["--strip-debug" "--no-man-pages"
                                           "--no-header-files" "--compress=2"]})

    ;; Create a start script for the application
    (bundle/bin-script out-path 'badigeon.main
                       {;; Specify which OS type the line breaks/separators/file extensions should be formatted for.
                        :os-type bundle/posix-like
                        ;; The path script is written to, relative to the out-path.
                        :script-path "bin/run.sh"
                        ;; A header prefixed to the script content.
                        :script-header "#!/bin/sh\n"
                        ;; The java binary path used to start the application. Default to \"java\" or \"runtime/bin/java\" when a custom JRE runtime is found under the run directory.
                        :command "runtime/bin/java"
                        ;; The classpath option used by the java command.
                        :classpath "..:../lib/*"
                        ;; JVM options given to the java command.
                        :jvm-opts ["-Xmx1g"]
                        ;; Parameters given to the application main method.
                        :args ["some-argument"]})

    ;; Zip the bundle
    (badigeon.zip/zip out-path (str out-path ".zip"))))

(comment
  (-main)
  )