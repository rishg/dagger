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

import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeTraverser;
import dagger.Component;
import dagger.Reusable;
import dagger.Subcomponent;
import dagger.internal.codegen.BindingType.HasBindingType;
import dagger.internal.codegen.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.internal.codegen.Key.HasKey;
import dagger.producers.ProductionComponent;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

import static com.google.auto.common.MoreElements.getAnnotationMirror;
import static com.google.auto.common.MoreElements.hasModifiers;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.base.Verify.verify;
import static dagger.internal.codegen.BindingKey.Kind.CONTRIBUTION;
import static dagger.internal.codegen.ComponentDescriptor.isComponentContributionMethod;
import static dagger.internal.codegen.ComponentDescriptor.isComponentProductionMethod;
import static dagger.internal.codegen.ComponentDescriptor.ComponentMethodDescriptor.isOfKind;
import static dagger.internal.codegen.ComponentDescriptor.ComponentMethodKind.PRODUCTION_SUBCOMPONENT_BUILDER;
import static dagger.internal.codegen.ComponentDescriptor.ComponentMethodKind.SUBCOMPONENT_BUILDER;
import static dagger.internal.codegen.ComponentDescriptor.Kind.PRODUCTION_COMPONENT;
import static dagger.internal.codegen.ConfigurationAnnotations.getComponentDependencies;
import static dagger.internal.codegen.ContributionBinding.Kind.IS_SYNTHETIC_MULTIBINDING_KIND;
import static dagger.internal.codegen.Key.indexByKey;
import static dagger.internal.codegen.Scope.reusableScope;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * The canonical representation of a full-resolved graph.
 *
 * @author Gregory Kick
 */
@AutoValue
abstract class BindingGraph {
  abstract ComponentDescriptor componentDescriptor();
  abstract ImmutableMap<BindingKey, ResolvedBindings> resolvedBindings();
  abstract ImmutableMap<ExecutableElement, BindingGraph> subgraphs();

  /**
   * Returns the set of modules that are owned by this graph regardless of whether or not any of
   * their bindings are used in this graph. For graphs representing top-level {@link Component
   * components}, this set will be the same as
   * {@linkplain ComponentDescriptor#transitiveModules the component's transitive modules}. For
   * {@linkplain Subcomponent subcomponents}, this set will be the transitive modules that are not
   * owned by any of their ancestors.
   */
  abstract ImmutableSet<ModuleDescriptor> ownedModules();

  ImmutableSet<TypeElement> ownedModuleTypes() {
    return FluentIterable.from(ownedModules())
        .transform(ModuleDescriptor.getModuleElement())
        .toSet();
  }

  private static final TreeTraverser<BindingGraph> SUBGRAPH_TRAVERSER =
      new TreeTraverser<BindingGraph>() {
        @Override
        public Iterable<BindingGraph> children(BindingGraph node) {
          return node.subgraphs().values();
        }
      };

  /**
   * The types for which the component needs instances.
   * <ul>
   * <li>component dependencies
   * <li>{@linkplain #ownedModules() owned modules} with concrete instance bindings that are used in
   *     the graph
   * </ul>
   */
  ImmutableSet<TypeElement> componentRequirements() {
    return SUBGRAPH_TRAVERSER
        .preOrderTraversal(this)
        .transformAndConcat(RESOLVED_BINDINGS)
        .transformAndConcat(ResolvedBindings.CONTRIBUTION_BINDINGS)
        .filter(not(BindingDeclaration.bindingElementHasModifier(STATIC)))
        .filter(not(BindingDeclaration.bindingElementHasModifier(ABSTRACT)))
        .transformAndConcat(BindingDeclaration.CONTRIBUTING_MODULE)
        .filter(in(ownedModuleTypes()))
        .append(componentDescriptor().dependencies())
        .toSet();
  }

  private static final Function<BindingGraph, Iterable<ResolvedBindings>> RESOLVED_BINDINGS =
      new Function<BindingGraph, Iterable<ResolvedBindings>>() {
        @Override
        public Iterable<ResolvedBindings> apply(BindingGraph graph) {
          return graph.resolvedBindings().values();
        }
      };

  /**
   * Returns the {@link ComponentDescriptor}s for this component and its subcomponents.
   */
  ImmutableSet<ComponentDescriptor> componentDescriptors() {
    return SUBGRAPH_TRAVERSER
        .preOrderTraversal(this)
        .transform(
            new Function<BindingGraph, ComponentDescriptor>() {
              @Override
              public ComponentDescriptor apply(BindingGraph graph) {
                return graph.componentDescriptor();
              }
            })
        .toSet();
  }

  ImmutableSet<TypeElement> availableDependencies() {
    return FluentIterable.from(componentDescriptor().transitiveModuleTypes())
        .filter(not(hasModifiers(ABSTRACT)))
        .append(componentDescriptor().dependencies())
        .toSet();
  }

  static final class Factory {
    private final Elements elements;
    private final InjectBindingRegistry injectBindingRegistry;
    private final Key.Factory keyFactory;
    private final ProvisionBinding.Factory provisionBindingFactory;
    private final ProductionBinding.Factory productionBindingFactory;

    Factory(Elements elements,
        InjectBindingRegistry injectBindingRegistry,
        Key.Factory keyFactory,
        ProvisionBinding.Factory provisionBindingFactory,
        ProductionBinding.Factory productionBindingFactory) {
      this.elements = elements;
      this.injectBindingRegistry = injectBindingRegistry;
      this.keyFactory = keyFactory;
      this.provisionBindingFactory = provisionBindingFactory;
      this.productionBindingFactory = productionBindingFactory;
    }

    BindingGraph create(ComponentDescriptor componentDescriptor) {
      return create(Optional.<Resolver>absent(), componentDescriptor);
    }

    private BindingGraph create(
        Optional<Resolver> parentResolver, ComponentDescriptor componentDescriptor) {
      ImmutableSet.Builder<ContributionBinding> explicitBindingsBuilder = ImmutableSet.builder();
      ImmutableSet.Builder<DelegateDeclaration> delegatesBuilder = ImmutableSet.builder();

      // binding for the component itself
      TypeElement componentDefinitionType = componentDescriptor.componentDefinitionType();
      explicitBindingsBuilder.add(provisionBindingFactory.forComponent(componentDefinitionType));

      // Collect Component dependencies.
      Optional<AnnotationMirror> componentMirror =
          getAnnotationMirror(componentDefinitionType, Component.class)
              .or(getAnnotationMirror(componentDefinitionType, ProductionComponent.class));
      ImmutableSet<TypeElement> componentDependencyTypes = componentMirror.isPresent()
          ? MoreTypes.asTypeElements(getComponentDependencies(componentMirror.get()))
          : ImmutableSet.<TypeElement>of();
      for (TypeElement componentDependency : componentDependencyTypes) {
        explicitBindingsBuilder.add(provisionBindingFactory.forComponent(componentDependency));
        List<ExecutableElement> dependencyMethods =
            ElementFilter.methodsIn(elements.getAllMembers(componentDependency));
        for (ExecutableElement method : dependencyMethods) {
          // MembersInjection methods aren't "provided" explicitly, so ignore them.
          if (isComponentContributionMethod(elements, method)) {
            explicitBindingsBuilder.add(
                componentDescriptor.kind().equals(PRODUCTION_COMPONENT)
                        && isComponentProductionMethod(elements, method)
                    ? productionBindingFactory.forComponentMethod(method)
                    : provisionBindingFactory.forComponentMethod(method));
          }
        }
      }

      // Bindings for subcomponent builders.
      for (ComponentMethodDescriptor subcomponentMethodDescriptor :
          Iterables.filter(
              componentDescriptor.subcomponents().keySet(),
              isOfKind(SUBCOMPONENT_BUILDER, PRODUCTION_SUBCOMPONENT_BUILDER))) {
        explicitBindingsBuilder.add(
            provisionBindingFactory.forSubcomponentBuilderMethod(
                subcomponentMethodDescriptor.methodElement(),
                componentDescriptor.componentDefinitionType()));
      }

      ImmutableSet.Builder<MultibindingDeclaration> multibindingDeclarations =
          ImmutableSet.builder();

      // Collect transitive module bindings and multibinding declarations.
      for (ModuleDescriptor moduleDescriptor : componentDescriptor.transitiveModules()) {
        explicitBindingsBuilder.addAll(moduleDescriptor.bindings());
        multibindingDeclarations.addAll(moduleDescriptor.multibindingDeclarations());
        delegatesBuilder.addAll(moduleDescriptor.delegateDeclarations());
      }

      Resolver requestResolver =
          new Resolver(
              parentResolver,
              componentDescriptor,
              indexByKey(explicitBindingsBuilder.build()),
              indexByKey(multibindingDeclarations.build()),
              indexByKey(delegatesBuilder.build()));
      for (ComponentMethodDescriptor componentMethod : componentDescriptor.componentMethods()) {
        Optional<DependencyRequest> componentMethodRequest = componentMethod.dependencyRequest();
        if (componentMethodRequest.isPresent()) {
          requestResolver.resolve(componentMethodRequest.get());
        }
      }

      ImmutableMap.Builder<ExecutableElement, BindingGraph> subgraphsBuilder =
          ImmutableMap.builder();
      for (Entry<ComponentMethodDescriptor, ComponentDescriptor> subcomponentEntry :
          componentDescriptor.subcomponents().entrySet()) {
        subgraphsBuilder.put(
            subcomponentEntry.getKey().methodElement(),
            create(Optional.of(requestResolver), subcomponentEntry.getValue()));
      }

      for (ResolvedBindings resolvedBindings : requestResolver.getResolvedBindings().values()) {
        verify(
            resolvedBindings.owningComponent().equals(componentDescriptor),
            "%s is not owned by %s",
            resolvedBindings,
            componentDescriptor);
      }

      return new AutoValue_BindingGraph(
          componentDescriptor,
          requestResolver.getResolvedBindings(),
          subgraphsBuilder.build(),
          requestResolver.getOwnedModules());
    }

    private final class Resolver {
      final Optional<Resolver> parentResolver;
      final ComponentDescriptor componentDescriptor;
      final ImmutableSetMultimap<Key, ContributionBinding> explicitBindings;
      final ImmutableSet<ContributionBinding> explicitBindingsSet;
      final ImmutableSetMultimap<Key, ContributionBinding> explicitMultibindings;
      final ImmutableSetMultimap<Key, MultibindingDeclaration> multibindingDeclarations;
      final ImmutableSetMultimap<Key, DelegateDeclaration> delegateDeclarations;
      final ImmutableSetMultimap<Key, DelegateDeclaration> delegateMultibindingDeclarations;
      final Map<BindingKey, ResolvedBindings> resolvedBindings;
      final Deque<BindingKey> cycleStack = new ArrayDeque<>();
      final Cache<BindingKey, Boolean> dependsOnLocalMultibindingsCache =
          CacheBuilder.newBuilder().<BindingKey, Boolean>build();
      final Cache<Binding, Boolean> bindingDependsOnLocalMultibindingsCache =
          CacheBuilder.newBuilder().<Binding, Boolean>build();

      Resolver(
          Optional<Resolver> parentResolver,
          ComponentDescriptor componentDescriptor,
          ImmutableSetMultimap<Key, ContributionBinding> explicitBindings,
          ImmutableSetMultimap<Key, MultibindingDeclaration> multibindingDeclarations,
          ImmutableSetMultimap<Key, DelegateDeclaration> delegateDeclarations) {
        assert parentResolver != null;
        this.parentResolver = parentResolver;
        assert componentDescriptor != null;
        this.componentDescriptor = componentDescriptor;
        assert explicitBindings != null;
        this.explicitBindings = explicitBindings;
        this.explicitBindingsSet = ImmutableSet.copyOf(explicitBindings.values());
        assert multibindingDeclarations != null;
        this.multibindingDeclarations = multibindingDeclarations;
        assert delegateDeclarations != null;
        this.delegateDeclarations = delegateDeclarations;
        this.resolvedBindings = Maps.newLinkedHashMap();
        this.explicitMultibindings =
            multibindingsKeyedWithoutBindingIdentifiers(explicitBindingsSet);
        this.delegateMultibindingDeclarations =
            multibindingsKeyedWithoutBindingIdentifiers(delegateDeclarations.values());
      }

      /**
       * Returns the bindings that satisfy a given dependency request.
       *
       * <p>For {@link BindingKey.Kind#CONTRIBUTION} requests, returns all of:
       * <ul>
       * <li>All explicit bindings for:
       *     <ul>
       *     <li>the requested key
       *     <li>{@code Set<T>} if the requested key's type is {@code Set<Produced<T>>}
       *     <li>{@code Map<K, Provider<V>>} if the requested key's type is
       *         {@code Map<K, Producer<V>>}.
       *     </ul>
       *
       * <li>A synthetic binding that depends on {@code Map<K, Producer<V>>} if the requested key's
       *     type is {@code Map<K, V>} and there are some explicit bindings for
       *     {@code Map<K, Producer<V>>}.
       *
       * <li>A synthetic binding that depends on {@code Map<K, Provider<V>>} if the requested key's
       *     type is {@code Map<K, V>} and there are some explicit bindings for
       *     {@code Map<K, Provider<V>>} but no explicit bindings for {@code Map<K, Producer<V>>}.
       *
       * <li>An implicit {@link Inject @Inject}-annotated constructor binding if there is one and
       *     there are no explicit bindings or synthetic bindings.
       * </ul>
       *
       * <p>For {@link BindingKey.Kind#MEMBERS_INJECTION} requests, returns the
       * {@link MembersInjectionBinding} for the type.
       */
      ResolvedBindings lookUpBindings(DependencyRequest request) {
        BindingKey bindingKey = request.bindingKey();
        Key requestKey = bindingKey.key();
        switch (bindingKey.kind()) {
          case CONTRIBUTION:
            Set<ContributionBinding> contributionBindings = new LinkedHashSet<>();
            ImmutableSet.Builder<ContributionBinding> multibindingsBuilder = ImmutableSet.builder();
            ImmutableSet.Builder<MultibindingDeclaration> multibindingDeclarationsBuilder =
                ImmutableSet.builder();

            for (Key key : keysMatchingRequest(requestKey)) {
              contributionBindings.addAll(getExplicitBindings(key));
              contributionBindings.addAll(getDelegateBindings(key));

              multibindingsBuilder.addAll(getExplicitMultibindings(key));
              multibindingsBuilder.addAll(getDelegateMultibindings(key));

              multibindingDeclarationsBuilder.addAll(getMultibindingDeclarations(key));
            }

            if (shouldCreateSyntheticMapOfValuesBinding(
                keyFactory.implicitMapProducerKeyFrom(requestKey))) {
              /* If the binding key is Map<K, V> or Map<K, Produced<V>> and there are some explicit
               * Map<K, Producer<V>> bindings or multibinding declarations, then add the synthetic
               * binding that depends on Map<K, Producer<V>>. */
              contributionBindings.add(
                  productionBindingFactory.syntheticMapOfValuesOrProducedBinding(request));
            } else if (shouldCreateSyntheticMapOfValuesBinding(
                keyFactory.implicitMapProviderKeyFrom(requestKey))) {
              /* If the binding key is Map<K, V> and there are some explicit Map<K, Provider<V>>
               * bindings or multibinding declarations but no explicit Map<K, Producer<V>> bindings
               * or multibinding declarations, then add the synthetic binding that depends on
               * Map<K, Provider<V>>. */
              contributionBindings.add(
                  provisionBindingFactory.syntheticMapOfValuesBinding(request));
            }

            ImmutableSet<ContributionBinding> multibindings = multibindingsBuilder.build();
            ImmutableSet<MultibindingDeclaration> multibindingDeclarations =
                multibindingDeclarationsBuilder.build();

            Iterable<? extends HasBindingType> multibindingsAndDeclarations =
                Iterables.concat(multibindings, multibindingDeclarations);
            if (Iterables.any(
                multibindingsAndDeclarations, BindingType.isOfType(BindingType.PRODUCTION))) {
              /* If there are production multibindings, add a synthetic binding that depends on each
               * individual multibinding. */
              contributionBindings.add(
                  productionBindingFactory.syntheticMultibinding(request, multibindings));
            } else if (Iterables.any(
                multibindingsAndDeclarations, BindingType.isOfType(BindingType.PROVISION))) {
              /* If there are provision multibindings but not production ones, add a synthetic
               * binding that depends on each individual multibinding. */
              contributionBindings.add(
                  provisionBindingFactory.syntheticMultibinding(request, multibindings));
            }

            /* If there are still no bindings, look for an implicit @Inject- constructed binding if
             * there is one. */
            if (contributionBindings.isEmpty()) {
              contributionBindings.addAll(
                  injectBindingRegistry.getOrFindProvisionBinding(requestKey).asSet());
            }

            return ResolvedBindings.forContributionBindings(
                bindingKey,
                componentDescriptor,
                indexBindingsByOwningComponent(request, ImmutableSet.copyOf(contributionBindings)),
                multibindingDeclarations);

          case MEMBERS_INJECTION:
            // no explicit deps for members injection, so just look it up
            Optional<MembersInjectionBinding> binding =
                injectBindingRegistry.getOrFindMembersInjectionBinding(requestKey);
            return binding.isPresent()
                ? ResolvedBindings.forMembersInjectionBinding(
                    bindingKey, componentDescriptor, binding.get())
                : ResolvedBindings.noBindings(bindingKey, componentDescriptor);
          default:
            throw new AssertionError();
        }
      }

      private Iterable<Key> keysMatchingRequest(Key requestKey) {
        return ImmutableSet.<Key>builder()
            .add(requestKey)
            .addAll(keyFactory.implicitSetKeyFromProduced(requestKey).asSet())
            .addAll(keyFactory.implicitProviderMapKeyFromProducer(requestKey).asSet())
            .build();
      }

      private boolean shouldCreateSyntheticMapOfValuesBinding(Optional<Key> maybeKey) {
        if (!maybeKey.isPresent()) {
          return false;
        }

        Key key = maybeKey.get();
        if (!getExplicitMultibindings(key).isEmpty()
            || !getMultibindingDeclarations(key).isEmpty()) {
          return true;
        }

        for (ContributionBinding delegateMultibinding : getDelegateMultibindings(key)) {
          if (delegateMultibinding.key().withoutBindingMethodIdentifier().equals(key)) {
            return true;
          }
        }
        return false;
      }

      private ImmutableSet<ContributionBinding> createDelegateBindings(
          ImmutableSet<DelegateDeclaration> delegateDeclarations) {
        ImmutableSet.Builder<ContributionBinding> builder = ImmutableSet.builder();
        for (DelegateDeclaration delegateDeclaration : delegateDeclarations) {
          DependencyRequest delegateRequest = delegateDeclaration.delegateRequest();
          ResolvedBindings resolvedDelegate = lookUpBindings(delegateRequest);
          for (ContributionBinding explicitDelegate : resolvedDelegate.contributionBindings()) {
            switch (explicitDelegate.bindingType()) {
              case PRODUCTION:
                builder.add(
                    productionBindingFactory.delegate(
                        delegateDeclaration, (ProductionBinding) explicitDelegate));
                break;
              case PROVISION:
                builder.add(
                    provisionBindingFactory.delegate(
                        delegateDeclaration, (ProvisionBinding) explicitDelegate));
                break;
              default:
                throw new AssertionError();
            }
          }
        }
        return builder.build();
      }

      private ImmutableSetMultimap<ComponentDescriptor, ContributionBinding>
          indexBindingsByOwningComponent(
              DependencyRequest request, Iterable<? extends ContributionBinding> bindings) {
        ImmutableSetMultimap.Builder<ComponentDescriptor, ContributionBinding> index =
            ImmutableSetMultimap.builder();
        for (ContributionBinding binding : bindings) {
          index.put(getOwningComponent(request, binding), binding);
        }
        return index.build();
      }

      /**
       * Returns the component that should contain the framework field for {@code binding}.
       *
       * <p>If {@code binding} is either not bound in an ancestor component or depends on
       * multibinding contributions in this component, returns this component.
       *
       * <p>Otherwise, resolves {@code request} in this component's parent in order to resolve any
       * multibinding contributions in the parent, and returns the parent-resolved
       * {@link ResolvedBindings#owningComponent(ContributionBinding)}.
       */
      private ComponentDescriptor getOwningComponent(
          DependencyRequest request, ContributionBinding binding) {
        if (isResolvedInParent(request, binding)
            && !new MultibindingDependencies().dependsOnLocalMultibindings(binding)) {
          ResolvedBindings parentResolvedBindings =
              parentResolver.get().resolvedBindings.get(request.bindingKey());
          return parentResolvedBindings.owningComponent(binding);
        } else {
          return componentDescriptor;
        }
      }

      /**
       * Returns {@code true} if {@code binding} is owned by an ancestor. If so,
       * {@linkplain #resolve(DependencyRequest) resolves} the request in this component's parent.
       * Don't resolve directly in the owning component in case it depends on multibindings in any
       * of its descendants.
       */
      private boolean isResolvedInParent(DependencyRequest request, ContributionBinding binding) {
        Optional<Resolver> owningResolver = getOwningResolver(binding);
        if (owningResolver.isPresent() && !owningResolver.get().equals(this)) {
          parentResolver.get().resolve(request);
          return true;
        } else {
          return false;
        }
      }

      private Optional<Resolver> getOwningResolver(ContributionBinding binding) {
        if (binding.scope().isPresent() && binding.scope().get().equals(reusableScope(elements))) {
          for (Resolver requestResolver : getResolverLineage().reverse()) {
            // If a @Reusable binding was resolved in an ancestor, use that component.
            if (requestResolver.resolvedBindings.containsKey(
                BindingKey.create(CONTRIBUTION, binding.key()))) {
              return Optional.of(requestResolver);
            }
          }
          // If a @Reusable binding was not resolved in any ancestor, resolve it here.
          return Optional.absent();
        }

        for (Resolver requestResolver : getResolverLineage().reverse()) {
          if (requestResolver.explicitBindingsSet.contains(binding)) {
            return Optional.of(requestResolver);
          }
        }

        // look for scope separately.  we do this for the case where @Singleton can appear twice
        // in the † compatibility mode
        Optional<Scope> bindingScope = binding.scope();
        if (bindingScope.isPresent()) {
          for (Resolver requestResolver : getResolverLineage().reverse()) {
            if (requestResolver.componentDescriptor.scopes().contains(bindingScope.get())) {
              return Optional.of(requestResolver);
            }
          }
        }
        return Optional.absent();
      }

      /** Returns the resolver lineage from parent to child. */
      private ImmutableList<Resolver> getResolverLineage() {
        List<Resolver> resolverList = Lists.newArrayList();
        for (Optional<Resolver> currentResolver = Optional.of(this);
            currentResolver.isPresent();
            currentResolver = currentResolver.get().parentResolver) {
          resolverList.add(currentResolver.get());
        }
        return ImmutableList.copyOf(Lists.reverse(resolverList));
      }

      /**
       * Returns the explicit {@link ContributionBinding}s that match the {@code requestKey} from
       * this and all ancestor resolvers.
       */
      private ImmutableSet<ContributionBinding> getExplicitBindings(Key requestKey) {
        ImmutableSet.Builder<ContributionBinding> explicitBindingsForKey = ImmutableSet.builder();
        for (Resolver resolver : getResolverLineage()) {
          explicitBindingsForKey.addAll(resolver.explicitBindings.get(requestKey));
        }
        return explicitBindingsForKey.build();
      }

      /**
       * Returns the explicit multibindings whose key (minus its
       * {@link Key#bindingMethodIdentifier()}) matches the {@code requestKey} from this and all
       * ancestor resolvers.
       */
      private ImmutableSet<ContributionBinding> getExplicitMultibindings(Key requestKey) {
        ImmutableSet.Builder<ContributionBinding> explicitMultibindingsForKey =
            ImmutableSet.builder();
        for (Resolver resolver : getResolverLineage()) {
          explicitMultibindingsForKey.addAll(resolver.explicitMultibindings.get(requestKey));
        }
        return explicitMultibindingsForKey.build();
      }

      /**
       * Returns the {@link MultibindingDeclaration}s that match the {@code key} from this and all
       * ancestor resolvers.
       */
      private ImmutableSet<MultibindingDeclaration> getMultibindingDeclarations(Key key) {
        ImmutableSet.Builder<MultibindingDeclaration> multibindingDeclarations =
            ImmutableSet.builder();
        for (Resolver resolver : getResolverLineage()) {
          multibindingDeclarations.addAll(resolver.multibindingDeclarations.get(key));
        }
        return multibindingDeclarations.build();
      }

      private ImmutableSet<ContributionBinding> getDelegateBindings(Key requestKey) {
        Key delegateDeclarationKey = keyFactory.convertToDelegateKey(requestKey);
        ImmutableSet.Builder<ContributionBinding> delegateBindings = ImmutableSet.builder();
        for (Resolver resolver : getResolverLineage()) {
          delegateBindings.addAll(
              createDelegateBindings(resolver.delegateDeclarations.get(delegateDeclarationKey)));
        }
        return delegateBindings.build();
      }

      private ImmutableSet<ContributionBinding> getDelegateMultibindings(Key requestKey) {
        if (MapType.isMap(requestKey) && !MapType.from(requestKey).valuesAreFrameworkType()) {
          // There are no @Binds @IntoMap delegate declarations for Map<K, V> requests. All @IntoMap
          // requests must be for Map<K, Framework<V>>.
          return ImmutableSet.of();
        }
        Key delegateDeclarationKey = keyFactory.convertToDelegateKey(requestKey);
        ImmutableSet.Builder<ContributionBinding> delegateMultibindings = ImmutableSet.builder();
        for (Resolver resolver : getResolverLineage()) {
          delegateMultibindings.addAll(
              createDelegateBindings(
                  resolver.delegateMultibindingDeclarations.get(delegateDeclarationKey)));
        }
        return delegateMultibindings.build();
      }

      private Optional<ResolvedBindings> getPreviouslyResolvedBindings(
          final BindingKey bindingKey) {
        Optional<ResolvedBindings> result = Optional.fromNullable(resolvedBindings.get(bindingKey));
        if (result.isPresent()) {
          return result;
        } else if (parentResolver.isPresent()) {
          return parentResolver.get().getPreviouslyResolvedBindings(bindingKey);
        } else {
          return Optional.absent();
        }
      }

      void resolve(DependencyRequest request) {
        BindingKey bindingKey = request.bindingKey();

        // If we find a cycle, stop resolving. The original request will add it with all of the
        // other resolved deps.
        if (cycleStack.contains(bindingKey)) {
          return;
        }

        // If the binding was previously resolved in this (sub)component, don't resolve it again.
        if (resolvedBindings.containsKey(bindingKey)) {
          return;
        }

        /* If the binding was previously resolved in a supercomponent, then we may be able to avoid
         * resolving it here and just depend on the supercomponent resolution.
         *
         * 1. If it depends on multibindings with contributions from this subcomponent, then we have
         *    to resolve it in this subcomponent so that it sees the local contributions.
         *
         * 2. If there are any explicit bindings in this component, they may conflict with those in
         *    the supercomponent, so resolve them here so that conflicts can be caught.
         */
        if (getPreviouslyResolvedBindings(bindingKey).isPresent()) {
          /* Resolve in the parent in case there are multibinding contributions or conflicts in some
           * component between this one and the previously-resolved one. */
          parentResolver.get().resolve(request);
          if (!new MultibindingDependencies().dependsOnLocalMultibindings(bindingKey)
              && getExplicitBindings(bindingKey.key()).isEmpty()) {
            /* Cache the inherited parent component's bindings in case resolving at the parent found
             * bindings in some component between this one and the previously-resolved one. */
            ResolvedBindings inheritedBindings =
                getPreviouslyResolvedBindings(bindingKey).get().asInheritedIn(componentDescriptor);
            resolvedBindings.put(bindingKey, inheritedBindings);
            return;
          }
        }

        cycleStack.push(bindingKey);
        try {
          ResolvedBindings bindings = lookUpBindings(request);
          for (Binding binding : bindings.ownedBindings()) {
            for (DependencyRequest dependency : binding.implicitDependencies()) {
              resolve(dependency);
            }
          }
          resolvedBindings.put(bindingKey, bindings);
        } finally {
          cycleStack.pop();
        }
      }

      ImmutableMap<BindingKey, ResolvedBindings> getResolvedBindings() {
        ImmutableMap.Builder<BindingKey, ResolvedBindings> resolvedBindingsBuilder =
            ImmutableMap.builder();
        resolvedBindingsBuilder.putAll(resolvedBindings);
        if (parentResolver.isPresent()) {
          Collection<ResolvedBindings> bindingsResolvedInParent =
              Maps.difference(parentResolver.get().getResolvedBindings(), resolvedBindings)
                  .entriesOnlyOnLeft()
                  .values();
          for (ResolvedBindings resolvedInParent : bindingsResolvedInParent) {
            resolvedBindingsBuilder.put(
                resolvedInParent.bindingKey(),
                resolvedInParent.asInheritedIn(componentDescriptor));
          }
        }
        return resolvedBindingsBuilder.build();
      }

      ImmutableSet<ModuleDescriptor> getInheritedModules() {
        return parentResolver.isPresent()
            ? Sets.union(
                    parentResolver.get().getInheritedModules(),
                    parentResolver.get().componentDescriptor.transitiveModules())
                .immutableCopy()
            : ImmutableSet.<ModuleDescriptor>of();
      }

      ImmutableSet<ModuleDescriptor> getOwnedModules() {
        return Sets.difference(componentDescriptor.transitiveModules(), getInheritedModules())
            .immutableCopy();
      }

      private final class MultibindingDependencies {
        private final Set<Object> cycleChecker = new HashSet<>();

        /**
         * Returns {@code true} if {@code bindingKey} previously resolved to multibindings with
         * contributions declared within this component's modules, or if any of its unscoped
         * dependencies depend on such local multibindings.
         *
         * <p>We don't care about scoped dependencies because they will never depend on
         * multibindings with contributions from subcomponents.
         *
         * @throws IllegalArgumentException if {@link #getPreviouslyResolvedBindings(BindingKey)} is
         *     absent
         */
        boolean dependsOnLocalMultibindings(final BindingKey bindingKey) {
          checkArgument(
              getPreviouslyResolvedBindings(bindingKey).isPresent(),
              "no previously resolved bindings in %s for %s",
              Resolver.this,
              bindingKey);
          // Don't recur infinitely if there are valid cycles in the dependency graph.
          if (!cycleChecker.add(bindingKey)) {
            return false;
          }
          try {
            return dependsOnLocalMultibindingsCache.get(
                bindingKey,
                new Callable<Boolean>() {
                  @Override
                  public Boolean call() {
                    ResolvedBindings previouslyResolvedBindings =
                        getPreviouslyResolvedBindings(bindingKey).get();
                    if (isMultibindingsWithLocalContributions(previouslyResolvedBindings)) {
                      return true;
                    }

                    for (Binding binding : previouslyResolvedBindings.bindings()) {
                      if (dependsOnLocalMultibindings(binding)) {
                        return true;
                      }
                    }
                    return false;
                  }
                });
          } catch (ExecutionException e) {
            throw new AssertionError(e);
          }
        }

        /**
         * Returns {@code true} if {@code binding} is unscoped (or has {@link Reusable @Reusable}
         * scope) and depends on multibindings with contributions declared within this component's
         * modules, or if any of its unscoped or {@link Reusable @Reusable} scoped dependencies
         * depend on such local multibindings.
         *
         * <p>We don't care about non-reusable scoped dependencies because they will never depend on
         * multibindings with contributions from subcomponents.
         */
        boolean dependsOnLocalMultibindings(final Binding binding) {
          if (!cycleChecker.add(binding)) {
            return false;
          }
          try {
            return bindingDependsOnLocalMultibindingsCache.get(
                binding,
                new Callable<Boolean>() {
                  @Override
                  public Boolean call() {
                    if ((!binding.scope().isPresent()
                            || binding.scope().get().equals(reusableScope(elements)))
                        // TODO(beder): Figure out what happens with production subcomponents.
                        && !binding.bindingType().equals(BindingType.PRODUCTION)) {
                      for (DependencyRequest dependency : binding.implicitDependencies()) {
                        if (dependsOnLocalMultibindings(dependency.bindingKey())) {
                          return true;
                        }
                      }
                    }
                    return false;
                  }
                });
          } catch (ExecutionException e) {
            throw new AssertionError(e);
          }
        }

        private boolean isMultibindingsWithLocalContributions(ResolvedBindings resolvedBindings) {
          return FluentIterable.from(resolvedBindings.contributionBindings())
                  .transform(ContributionBinding.KIND)
                  .anyMatch(IS_SYNTHETIC_MULTIBINDING_KIND)
              && explicitMultibindings.containsKey(resolvedBindings.key());
        }
      }
    }

    /**
     * Selects each item in {@code haveKeys} that has a {@link Key#bindingMethodIdentifier()} and
     * indexes them by its {@link HasKey#key()}, where each key has its {@link
     * dagger.internal.codegen.Key.BindingMethodIdentifier} removed.
     */
    static <T extends HasKey> ImmutableSetMultimap<Key, T>
        multibindingsKeyedWithoutBindingIdentifiers(Iterable<T> haveKeys) {
      ImmutableSetMultimap.Builder<Key, T> builder = ImmutableSetMultimap.builder();
      for (T hasKey : haveKeys) {
        if (hasKey.key().bindingMethodIdentifier().isPresent()) {
          builder.put(hasKey.key().withoutBindingMethodIdentifier(), hasKey);
        }
      }
      return builder.build();
    }
  }
}
