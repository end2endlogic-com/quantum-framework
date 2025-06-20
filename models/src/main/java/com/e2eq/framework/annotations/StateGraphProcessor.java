package com.e2eq.framework.annotations;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;

@SupportedAnnotationTypes("com.e2eq.framework.annotations.StateGraph")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class StateGraphProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(StateGraph.class)) {
            Element enclosingElement = element.getEnclosingElement();
            if (enclosingElement.getKind() == ElementKind.CLASS) {
                if (enclosingElement.getAnnotation(Stateful.class) == null) {
                    processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@StateGraph can only be used within classes annotated with @Stateful",
                        element
                    );
                }
            }
        }

        return true;
    }
}
