package io.github.danthe1st.spring_validator.processors;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic.Kind;

@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class EndpointChecker extends AbstractProcessor {

	private static final List<String> ALL_METHODS = List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS",
			"TRACE");

	private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^{}/]+)\\}");

	private static final Map<String, List<String>> ENDPOINT_ANNOTATIONS = Map.of(
			"org.springframework.web.bind.annotation.RequestMapping", ALL_METHODS,
			"org.springframework.web.bind.annotation.GetMapping", List.of("GET"),
			"org.springframework.web.bind.annotation.PostMapping", List.of("POST"),
			"org.springframework.web.bind.annotation.PutMapping", List.of("PUT"),
			"org.springframework.web.bind.annotation.DeleteMapping", List.of("DELETE"),
			"org.springframework.web.bind.annotation.PatchMapping", List.of("PATCH"));

	private record FoundPath(String path, List<String> methods) {
	}

	@Override
	public Set<String> getSupportedAnnotationTypes() {
		Set<String> supportedTypes = new HashSet<>();
		supportedTypes.add("org.springframework.stereotype.Controller");
		supportedTypes.add("org.springframework.web.bind.annotation.RestController");
		for (String type : ENDPOINT_ANNOTATIONS.keySet()) {
			supportedTypes.add(type);
		}
		return supportedTypes;
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		for (TypeElement annotation : annotations) {
			for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(annotation)) {
				try {
					switch (annotatedElement.getKind()) {
						case METHOD: {
							ExecutableElement method = (ExecutableElement) annotatedElement;
							List<FoundPath> paths = findPathsFromMethod(method);
							checkForCollisions(method, paths);
							checkPathMatches(method, paths);
							break;
						}
						case CLASS: {
							boolean hasEndpoint = false;
							for (Element element : annotatedElement.getEnclosedElements()) {
								if (element.getKind() == ElementKind.METHOD && findAnnotation(element,
										"org.springframework.web.bind.annotation.RequestMapping") != null) {
									hasEndpoint = true;
								}
							}
							if (!hasEndpoint) {
								processingEnv.getMessager().printMessage(Kind.MANDATORY_WARNING,
										"@" + annotation.getSimpleName() + " without endpoint",
										annotatedElement);
							}
						}
						default: {
							// don't process unknown elements
						}
					}
				} catch (RuntimeException e) {
					e.printStackTrace();
					try (StringWriter sw = new StringWriter();
							PrintWriter pw = new PrintWriter(sw)) {
						processingEnv.getMessager().printMessage(Kind.ERROR, "An exception occured: \n" + sw,
								annotatedElement);
					} catch (IOException e1) {
						throw new UncheckedIOException(e1);
					}
				}

			}
		}

		if (roundEnv.processingOver()) {
			foundPaths = new HashMap<>();
		}

		return false;
	}

	private void checkPathMatches(ExecutableElement method, List<FoundPath> paths) {
		for (FoundPath path : paths) {
			Matcher matcher = PATH_PARAM_PATTERN.matcher(path.path());
			Set<String> expectedPathParams = new HashSet<>();
			while (matcher.find()) {
				expectedPathParams.add(matcher.group(1));
			}
			int numOfUnnamedPathParams = 0;
			for (VariableElement parameter : method.getParameters()) {
				AnnotationMirror pathVariableAnnotation = findAnnotation(parameter,
						"org.springframework.web.bind.annotation.PathVariable");
				if (pathVariableAnnotation != null) {
					Set<String> variableNameIdentifiers = Set.of("value", "name");

					StringBuilder variableName = new StringBuilder();
					pathVariableAnnotation.getElementValues().forEach((argName, argValue) -> {
						if (variableNameIdentifiers.contains(argName.getSimpleName().toString())
								&& (argValue.getValue() instanceof String s)) {
							if (variableName.isEmpty()) {
								variableName.append(s);
							} else {
								processingEnv.getMessager().printMessage(Kind.ERROR, "Duplicate name for path variable",
										parameter);
							}
						}
					});
					String varName;
					if (variableName.isEmpty()) {
						varName = parameter.getSimpleName().toString();
					} else {
						varName = variableName.toString();
					}

					if (!expectedPathParams.remove(varName)) {
						numOfUnnamedPathParams++;
						processingEnv.getMessager().printMessage(Kind.WARNING,
								"@PathVariable " + varName + " cannot be found in path " + path.path(),
								parameter);
					}
				}
			}
			if (numOfUnnamedPathParams != expectedPathParams.size()) {
				processingEnv.getMessager().printMessage(Kind.ERROR,
						"@PathVariables do not match endpoint path " + path.path(), method);
			}
		}
	}

	private Map<String, ExecutableElement> foundPaths = new HashMap<>();

	private void checkForCollisions(ExecutableElement annotatedElement, List<FoundPath> paths) {
		for (FoundPath foundPath : paths) {
			for (String method : foundPath.methods()) {
				String path = method + " " + PATH_PARAM_PATTERN.matcher(foundPath.path()).replaceAll("*");
				ExecutableElement oldValue = foundPaths.putIfAbsent(path, annotatedElement);
				if (oldValue != null && oldValue != annotatedElement) {
					processingEnv.getMessager().printMessage(Kind.ERROR, "Duplicate path: " + path,
							annotatedElement);
					processingEnv.getMessager().printMessage(Kind.ERROR, "Duplicate path: " + path,
							oldValue);
				}
//				processingEnv.getMessager().printMessage(Kind.OTHER, "Path: " + path, annotatedElement);
			}
		}
	}

	private List<FoundPath> findPathsFromMethod(ExecutableElement method) {
		Element outerElement = method.getEnclosingElement();
		List<FoundPath> classLevelPaths;
		if (outerElement.getKind() == ElementKind.CLASS) {
			if (findAnnotation(outerElement, "org.springframework.stereotype.Controller") == null) {
				processingEnv.getMessager().printMessage(Kind.MANDATORY_WARNING, "endpoint outside of controller",
						method);// TODO move out
			}
			classLevelPaths = emptyPathIfNoPaths(findPaths(outerElement));
		} else {
			processingEnv.getMessager().printMessage(Kind.ERROR, "expected to be enclosed in class", method);
			return Collections.emptyList();
		}
		List<FoundPath> methodPaths = emptyPathIfNoPaths(findPaths(method));

		List<FoundPath> ret = new ArrayList<>();
		for (FoundPath classLevelPath : classLevelPaths) {
			for (FoundPath methodLevelPath : methodPaths) {
				String path = classLevelPath.path() + methodLevelPath.path();
				if (!path.startsWith("/")) {
					path = "/" + path;
				}
				ret.add(new FoundPath(path, methodLevelPath.methods()));
			}
		}
		return ret;
	}

	private List<FoundPath> emptyPathIfNoPaths(List<FoundPath> classLevelPaths) {
		if (classLevelPaths.isEmpty()) {
			classLevelPaths = List.of(new FoundPath("", ALL_METHODS));
		}
		return classLevelPaths;
	}

	private AnnotationMirror findAnnotation(Element element, String annotationToFind) {
		Queue<AnnotationMirror> toSearch = new LinkedList<>(element.getAnnotationMirrors());
		Set<AnnotationMirror> searched = new HashSet<>();
		while (!toSearch.isEmpty()) {
			AnnotationMirror mirror = toSearch.poll();
			if (searched.add(mirror)) {
				String annotationClassName = mirror.getAnnotationType().asElement().toString();
				if (annotationToFind.equals(annotationClassName)) {
					return mirror;
				}
				toSearch.addAll(mirror.getAnnotationType().asElement().getAnnotationMirrors());
			}
		}
		return null;
	}

	private List<FoundPath> findPaths(Element element) {
		List<FoundPath> ret = new ArrayList<>();
		for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
			String annotationClassName = mirror.getAnnotationType().asElement().toString();
			if (ENDPOINT_ANNOTATIONS.containsKey(annotationClassName)) {
				List<String> methods = new ArrayList<>(ENDPOINT_ANNOTATIONS.get(annotationClassName));

				Set<String> pathIdentifiers = Set.of("value", "path");
				mirror.getElementValues().forEach((argName, argValue) -> {
					if (pathIdentifiers.contains(argName.getSimpleName().toString())) {
						addPathsFromPathParam(element, ret, methods, argValue);
					} else if ("method".equals(argName.getSimpleName().toString())) {
						extractRequestMethodFromMethodParam(element, methods, argValue);
					}
				});
			}
		}
		return ret;
	}

	private void addPathsFromPathParam(Element element, List<FoundPath> ret, List<String> methods,
			AnnotationValue argValue) {
		Object value = argValue.getValue();
		if ((value instanceof List<?> paths)) {
			for (Object foundPath : paths) {
				String asString = foundPath.toString();
				if (asString.startsWith("\"") && asString.endsWith("\"")) {
					asString = asString.substring(1, asString.length() - 1);
				}
				ret.add(new FoundPath(asString, methods));
			}
		} else {
			processingEnv.getMessager().printMessage(Kind.ERROR,
					"expected list of paths but got " + value.getClass(),
					element);
		}
	}

	private void extractRequestMethodFromMethodParam(Element element, List<String> methods, AnnotationValue argValue) {
		Object value = argValue.getValue();
		if ((value instanceof List<?> methodExtractor)) {
			if (!methodExtractor.isEmpty()) {
				methods.clear();
				for (Object method : methodExtractor) {
					String methodAsString = method.toString();
					int lastDot = methodAsString.lastIndexOf('.');
					if (lastDot != -1) {
						methodAsString = methodAsString.substring(lastDot + 1);
					}
					methods.add(methodAsString);
				}
			}
		} else {
			processingEnv.getMessager().printMessage(Kind.ERROR,
					"expected list of paths but got " + value.getClass(),
					element);
		}
	}

}
