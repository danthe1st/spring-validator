# Spring-validator

This annotation processor raises a compile-time error if multiple endpoints have the same path.

This project is not production-ready. Use with care.

### How to use
1. Clone this repository and run `mvn install`. This is necessary as this project is not deployed to a (public) Maven repository.
2. Add the following to the `<plugins>` section of your `pom.xml`:
	```xml
	<plugin>
		<groupId>org.apache.maven.plugins</groupId>
		<artifactId>maven-compiler-plugin</artifactId>
		<configuration>
			<annotationProcessorPaths>
				<annotationProcessorPath>
					<groupId>io.github.danthe1st</groupId>
					<artifactId>spring-validator</artifactId>
					<version>0.0.1-SNAPSHOT</version>
				</annotationProcessorPath>
			</annotationProcessorPaths>
		</configuration>
	</plugin>
	```
3. Make sure annotation processing is enabled in your IDE.


### Limitations

This project only detects endpoints annotated with Spring's default annotations (`@GetMapping`, `@RequestMapping`, ...).

