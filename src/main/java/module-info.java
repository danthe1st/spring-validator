import javax.annotation.processing.Processor;

import io.github.danthe1st.spring_validator.processors.EndpointChecker;

module io.github.danthe1st.spring_validator {
	requires java.compiler;

	provides Processor with EndpointChecker;
}