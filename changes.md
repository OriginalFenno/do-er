1.0.1   - Bump dependency versions; this fixes compile/runtime warnings from an early version of java-time
1.0.2   - Change project name to reflect being published on Clojars
1.0.3   - Update readme.md with artifact name
1.0.4   - Update project file with license, description, and URL
2.0.0a1 - Removed java-time as a dependency in favour of java.time interop; bumped other deps; reasonable WIP for
          durable pools; stop-task now checks for the existence of a task before trying to stop, preventing an
          exception; added notes for other changes planned/in-progress/under consideration
2.0.0a2 - Fixed an issue caused by a function being made private in error