/*
 * Copyright (C) 2014 Google, Inc.
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
package dagger.internal.codegen;

import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import dagger.Component;
import dagger.Module;
import dagger.Subcomponent;
import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleTypeVisitor6;
import javax.lang.model.util.Types;

import static com.google.auto.common.MoreElements.getAnnotationMirror;
import static dagger.internal.codegen.ConfigurationAnnotations.enclosedBuilders;
import static dagger.internal.codegen.ConfigurationAnnotations.getComponentModules;
import static dagger.internal.codegen.ConfigurationAnnotations.getTransitiveModules;
import static dagger.internal.codegen.Util.componentCanMakeNewInstances;
import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.ElementKind.INTERFACE;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.type.TypeKind.VOID;

/**
 * Performs superficial validation of the contract of the {@link Component} annotation.
 *
 * @author Gregory Kick
 */
final class ComponentValidator {
  private final Elements elements;
  private final Types types;
  private final ComponentDescriptor.Kind componentType;
  private final ModuleValidator moduleValidator;
  private final ComponentValidator subcomponentValidator;
  private final BuilderValidator subcomponentBuilderValidator;

  private ComponentValidator(Elements elements,
      Types types,
      ModuleValidator moduleValidator,
      BuilderValidator subcomponentBuilderValidator) {
    this.elements = elements;
    this.types = types;
    this.componentType = ComponentDescriptor.Kind.SUBCOMPONENT;
    this.moduleValidator = moduleValidator;
    this.subcomponentValidator = this;
    this.subcomponentBuilderValidator = subcomponentBuilderValidator;
  }

  private ComponentValidator(Elements elements,
      Types types,
      ModuleValidator moduleValidator,
      ComponentValidator subcomponentValidator,
      BuilderValidator subcomponentBuilderValidator) {
    this.elements = elements;
    this.types = types;
    this.componentType = ComponentDescriptor.Kind.COMPONENT;
    this.moduleValidator = moduleValidator;
    this.subcomponentValidator = subcomponentValidator;
    this.subcomponentBuilderValidator = subcomponentBuilderValidator;
  }

  static ComponentValidator createForComponent(Elements elements,
      Types types,
      ModuleValidator moduleValidator,
      ComponentValidator subcomponentValidator,
      BuilderValidator subcomponentBuilderValidator) {
    return new ComponentValidator(elements,
        types,
        moduleValidator,
        subcomponentValidator,
        subcomponentBuilderValidator);
  }

  static ComponentValidator createForSubcomponent(Elements elements,
      Types types,
      ModuleValidator moduleValidator,
      BuilderValidator subcomponentBuilderValidator) {
    return new ComponentValidator(elements,
        types,
        moduleValidator,
        subcomponentBuilderValidator);
  }

  @AutoValue
  static abstract class ComponentValidationReport {
    abstract Set<Element> referencedSubcomponents();
    abstract ValidationReport<TypeElement> report();
  }

  /**
   * Validates the given component subject. Also validates any referenced subcomponents that aren't
   * already included in the {@code validatedSubcomponents} set.
   */
  public ComponentValidationReport validate(final TypeElement subject,
      Set<? extends Element> validatedSubcomponents,
      Set<? extends Element> validatedSubcomponentBuilders) {
    ValidationReport.Builder<TypeElement> builder = ValidationReport.Builder.about(subject);

    if (!subject.getKind().equals(INTERFACE)
        && !(subject.getKind().equals(CLASS) && subject.getModifiers().contains(ABSTRACT))) {
      builder.addItem(String.format("@%s may only be applied to an interface or abstract class",
          componentType.annotationType().getSimpleName()), subject);
    }

    ImmutableList<DeclaredType> builders =
        enclosedBuilders(subject, componentType.builderAnnotationType());
    if (builders.size() > 1) {
      builder.addItem(
          String.format(ErrorMessages.builderMsgsFor(componentType).moreThanOne(), builders),
          subject);
    }

    DeclaredType subjectType = MoreTypes.asDeclared(subject.asType());

    // TODO(gak): This should use Util.findLocalAndInheritedMethods, otherwise
    // it can return a logical method multiple times (including overrides, etc.)
    List<? extends Element> members = elements.getAllMembers(subject);
    Multimap<Element, ExecutableElement> referencedSubcomponents = LinkedHashMultimap.create();
    for (ExecutableElement method : ElementFilter.methodsIn(members)) {
      if (method.getModifiers().contains(ABSTRACT)) {
        ExecutableType resolvedMethod =
            MoreTypes.asExecutable(types.asMemberOf(subjectType, method));
        List<? extends TypeMirror> parameterTypes = resolvedMethod.getParameterTypes();
        List<? extends VariableElement> parameters = method.getParameters();
        TypeMirror returnType = resolvedMethod.getReturnType();

        // abstract methods are ones we have to implement, so they each need to be validated
        // first, check the return type.  if it's a subcomponent, validate that method as such.
        Optional<AnnotationMirror> subcomponentAnnotation =
            checkForAnnotation(returnType, Subcomponent.class);
        Optional<AnnotationMirror> subcomponentBuilderAnnotation =
            checkForAnnotation(returnType, Subcomponent.Builder.class);
        if (subcomponentAnnotation.isPresent()) {
          referencedSubcomponents.put(MoreTypes.asElement(returnType), method);
          validateSubcomponentMethod(builder,
              method,
              parameters,
              parameterTypes,
              returnType,
              subcomponentAnnotation);
        } else if (subcomponentBuilderAnnotation.isPresent()) {
          referencedSubcomponents.put(MoreTypes.asElement(returnType).getEnclosingElement(),
              method);
          validateSubcomponentBuilderMethod(builder,
              method,
              parameters,
              returnType,
              validatedSubcomponentBuilders);
        } else {
          // if it's not a subcomponent...
          switch (parameters.size()) {
            case 0:
              // no parameters means that it is a provision method
              // basically, there are no restrictions here.  \o/
              break;
            case 1:
              // one parameter means that it's a members injection method
              TypeMirror onlyParameter = Iterables.getOnlyElement(parameterTypes);
              if (!(returnType.getKind().equals(VOID)
                  || types.isSameType(returnType, onlyParameter))) {
                builder.addItem(
                    "Members injection methods may only return the injected type or void.",
                    method);
              }
              break;
            default:
              // this isn't any method that we know how to implement...
              builder.addItem(
                  "This method isn't a valid provision method, members injection method or "
                      + "subcomponent factory method. Dagger cannot implement this method", method);
              break;
          }
        }
      }
    }

    for (Map.Entry<Element, Collection<ExecutableElement>> entry :
        referencedSubcomponents.asMap().entrySet()) {
      if (entry.getValue().size() > 1) {
        builder.addItem(
            String.format(
                ErrorMessages.SubcomponentBuilderMessages.INSTANCE.moreThanOneRefToSubcomponent(),
                entry.getKey(), entry.getValue()),
            subject);
      }
    }

    AnnotationMirror componentMirror =
        getAnnotationMirror(subject, componentType.annotationType()).get();
    ImmutableList<TypeMirror> moduleTypes = getComponentModules(componentMirror);
    moduleValidator.validateReferencedModules(subject, builder, moduleTypes);

    // Make sure we validate any subcomponents we're referencing, unless we know we validated
    // them already in this pass.
    // TODO(sameb): If subcomponents refer to each other and both aren't in
    //              'validatedSubcomponents' (e.g, both aren't compiled in this pass),
    //              then this can loop forever.
    ImmutableSet.Builder<Element> allSubcomponents =
        ImmutableSet.<Element>builder().addAll(referencedSubcomponents.keySet());
    for (Element subcomponent :
        Sets.difference(referencedSubcomponents.keySet(), validatedSubcomponents)) {
      ComponentValidationReport subreport = subcomponentValidator.validate(
          MoreElements.asType(subcomponent), validatedSubcomponents, validatedSubcomponentBuilders);
      builder.addItems(subreport.report().items());
      allSubcomponents.addAll(subreport.referencedSubcomponents());
    }

    return new AutoValue_ComponentValidator_ComponentValidationReport(allSubcomponents.build(),
        builder.build());
  }

  private void validateSubcomponentMethod(final ValidationReport.Builder<TypeElement> builder,
      ExecutableElement method,
      List<? extends VariableElement> parameters,
      List<? extends TypeMirror> parameterTypes,
      TypeMirror returnType,
      Optional<AnnotationMirror> subcomponentAnnotation) {
    ImmutableSet<TypeElement> moduleTypes =
        MoreTypes.asTypeElements(getComponentModules(subcomponentAnnotation.get()));

    // TODO(gak): This logic maybe/probably shouldn't live here as it requires us to traverse
    // subcomponents and their modules separately from how it is done in ComponentDescriptor and
    // ModuleDescriptor
    ImmutableSet<TypeElement> transitiveModules =
        getTransitiveModules(types, elements, moduleTypes);

    ImmutableSet<TypeElement> requiredModules =
        FluentIterable.from(transitiveModules)
            .filter(new Predicate<TypeElement>() {
              @Override public boolean apply(TypeElement input) {
                return !componentCanMakeNewInstances(input);
              }
            })
            .toSet();

    Set<TypeElement> variableTypes = Sets.newHashSet();

    for (int i = 0; i < parameterTypes.size(); i++) {
      VariableElement parameter = parameters.get(i);
      TypeMirror parameterType = parameterTypes.get(i);
      Optional<TypeElement> moduleType = parameterType.accept(
          new SimpleTypeVisitor6<Optional<TypeElement>, Void>() {
            @Override protected Optional<TypeElement> defaultAction(TypeMirror e, Void p) {
              return Optional.absent();
            }

            @Override public Optional<TypeElement> visitDeclared(DeclaredType t, Void p) {
              return MoreElements.isAnnotationPresent(t.asElement(), Module.class)
                  ? Optional.of(MoreTypes.asTypeElement(t))
                  : Optional.<TypeElement>absent();
            }
          }, null);
      if (moduleType.isPresent()) {
        if (variableTypes.contains(moduleType.get())) {
          builder.addItem(
              String.format(
                  "A module may only occur once an an argument in a Subcomponent factory "
                      + "method, but %s was already passed.",
                  moduleType.get().getQualifiedName()), parameter);
        }
        if (!transitiveModules.contains(moduleType.get())) {
          builder.addItem(
              String.format(
                  "%s is present as an argument to the %s factory method, but is not one of the"
                      + " modules used to implement the subcomponent.",
                  moduleType.get().getQualifiedName(),
                  MoreTypes.asTypeElement(returnType).getQualifiedName()),
              method);
        }
        variableTypes.add(moduleType.get());
      } else {
        builder.addItem(
            String.format(
                "Subcomponent factory methods may only accept modules, but %s is not.",
                parameterType),
            parameter);
      }
    }

    SetView<TypeElement> missingModules =
        Sets.difference(requiredModules, ImmutableSet.copyOf(variableTypes));
    if (!missingModules.isEmpty()) {
      builder.addItem(
          String.format(
              "%s requires modules which have no visible default constructors. "
                  + "Add the following modules as parameters to this method: %s",
              MoreTypes.asTypeElement(returnType).getQualifiedName(),
              Joiner.on(", ").join(missingModules)),
          method);
    }
  }

  private void validateSubcomponentBuilderMethod(ValidationReport.Builder<TypeElement> builder,
      ExecutableElement method, List<? extends VariableElement> parameters, TypeMirror returnType,
      Set<? extends Element> validatedSubcomponentBuilders) {

    if (!parameters.isEmpty()) {
      builder.addItem(
          ErrorMessages.SubcomponentBuilderMessages.INSTANCE.builderMethodRequiresNoArgs(), method);
    }

    // If we haven't already validated the subcomponent builder itself, validate it now.
    TypeElement builderElement = MoreTypes.asTypeElement(returnType);
    if (!validatedSubcomponentBuilders.contains(builderElement)) {
      // TODO(sameb): The builder validator right now assumes the element is being compiled
      // in this pass, which isn't true here.  We should change error messages to spit out
      // this method as the subject and add the original subject to the message output.
      builder.addItems(subcomponentBuilderValidator.validate(builderElement).items());
    }
  }

  private Optional<AnnotationMirror> checkForAnnotation(TypeMirror type,
      final Class<? extends Annotation> annotation) {
    return type.accept(new SimpleTypeVisitor6<Optional<AnnotationMirror>, Void>() {
      @Override
      protected Optional<AnnotationMirror> defaultAction(TypeMirror e, Void p) {
        return Optional.absent();
      }

      @Override
      public Optional<AnnotationMirror> visitDeclared(DeclaredType t, Void p) {
        return MoreElements.getAnnotationMirror(t.asElement(), annotation);
      }
    }, null);
  }
}
