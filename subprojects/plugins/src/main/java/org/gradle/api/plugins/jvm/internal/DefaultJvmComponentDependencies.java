/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.plugins.jvm.internal;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.MinimalExternalModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.internal.catalog.DependencyBundleValueSource;
import org.gradle.api.internal.provider.DefaultValueSourceProviderFactory;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.jvm.JvmComponentDependencies;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderConvertible;
import org.gradle.api.provider.ValueSource;
import org.gradle.internal.Cast;
import org.gradle.internal.metaobject.DynamicInvokeResult;
import org.gradle.internal.metaobject.DynamicInvokeResult.AdditionalContext;
import org.gradle.internal.metaobject.MethodAccess;
import org.gradle.internal.metaobject.MethodMixIn;
import org.gradle.metaobject.ProvidesMissingMethodContext;
import org.gradle.util.internal.CollectionUtils;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

public abstract class DefaultJvmComponentDependencies implements JvmComponentDependencies, MethodMixIn, ProvidesMissingMethodContext {
    private final Configuration implementation;
    private final Configuration compileOnly;
    private final Configuration runtimeOnly;
    private final Configuration annotationProcessor;

    @Inject
    public DefaultJvmComponentDependencies(Configuration implementation, Configuration compileOnly, Configuration runtimeOnly, Configuration annotationProcessor) {
        this.implementation = implementation;
        this.compileOnly = compileOnly;
        this.runtimeOnly = runtimeOnly;
        this.annotationProcessor = annotationProcessor;
    }

    @Inject
    protected abstract DependencyHandler getDependencyHandler();

    @Inject
    protected abstract ConfigurationContainer getConfigurationContainer();

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Override
    public void implementation(Object dependency) {
        implementation(dependency, null);
    }

    @Override
    public void implementation(Object dependency, @Nullable Action<? super Dependency> configuration) {
        doAdd(implementation, dependency, configuration);
    }

    @Override
    public void runtimeOnly(Object dependency) {
        runtimeOnly(dependency, null);
    }

    @Override
    public void runtimeOnly(Object dependency, @Nullable Action<? super Dependency> configuration) {
        doAdd(runtimeOnly, dependency, configuration);
    }

    @Override
    public void compileOnly(Object dependency) {
        compileOnly(dependency, null);
    }

    @Override
    public void compileOnly(Object dependency, @Nullable Action<? super Dependency> configuration) {
        doAdd(compileOnly, dependency, configuration);
    }

    @Override
    public void annotationProcessor(Object dependency) {
        annotationProcessor(dependency, null);
    }

    @Override
    public void annotationProcessor(Object dependency, @Nullable Action<? super Dependency> configuration) {
        doAdd(annotationProcessor, dependency, configuration);
    }

    private void doAdd(Configuration bucket, Object dependency, @Nullable Action<? super Dependency> configuration) {
        if (dependency instanceof ProviderConvertible<?>) {
            doAddLazy(bucket, ((ProviderConvertible<?>) dependency).asProvider(), configuration);
        } else if (dependency instanceof Provider<?>) {
            doAddLazy(bucket, (Provider<?>) dependency, configuration);
        } else {
            doAddEager(bucket, dependency, configuration);
        }
    }

    private void doAddEager(Configuration bucket, Object dependency, @Nullable Action<? super Dependency> configuration) {
        Dependency created = create(dependency, configuration);
        bucket.getDependencies().add(created);
    }

    private void doAddLazy(Configuration bucket, Provider<?> dependencyProvider, @Nullable Action<? super Dependency> configuration) {
        if (dependencyProvider instanceof DefaultValueSourceProviderFactory.ValueSourceProvider) {
            Class<? extends ValueSource<?, ?>> valueSourceType = ((DefaultValueSourceProviderFactory.ValueSourceProvider<?, ?>) dependencyProvider).getValueSourceType();
            if (valueSourceType.isAssignableFrom(DependencyBundleValueSource.class)) {
                doAddListProvider(bucket, dependencyProvider, configuration);
                return;
            }
        }
        Provider<Dependency> lazyDependency = dependencyProvider.map(mapDependencyProvider(bucket, configuration));
        bucket.getDependencies().addLater(lazyDependency);
    }

    private void doAddListProvider(Configuration bucket, Provider<?> dependency, @Nullable Action<? super Dependency> configuration) {
        // workaround for the fact that mapping to a list will not create a `CollectionProviderInternal`
        final ListProperty<Dependency> dependencies = getObjectFactory().listProperty(Dependency.class);
        dependencies.set(dependency.map(notation -> {
            List<MinimalExternalModuleDependency> deps = Cast.uncheckedCast(notation);
            return deps.stream().map(d -> create(d, configuration)).collect(Collectors.toList());
        }));
        bucket.getDependencies().addAllLater(dependencies);
    }

    private <T> Transformer<Dependency, T> mapDependencyProvider(Configuration bucket, @Nullable Action<? super Dependency> configuration) {
        return lazyNotation -> {
            if (lazyNotation instanceof Configuration) {
                throw new InvalidUserDataException("Adding a configuration as a dependency using a provider isn't supported. You should call " + bucket.getName() + ".extendsFrom(" + ((Configuration) lazyNotation).getName() + ") instead");
            }
            return create(lazyNotation, configuration);
        };
    }

    private Dependency create(Object dependency, @Nullable Action<? super Dependency> configuration) {
        final Dependency created = getDependencyHandler().create(dependency);
        if (configuration != null) {
            configuration.execute(created);
        }
        return created;
    }

    @Override
    public AdditionalContext getAdditionalContext(String name, Object... arguments) {
        return AdditionalContext.forString("Don't call this here!");
    }

    @Override
    public MethodAccess getAdditionalMethods() {
        ConfigurationContainer configurationContainer = getConfigurationContainer();
        return new MethodAccess() {
            @Override
            public boolean hasMethod(String name, Object... arguments) {
                return arguments.length != 0 && configurationContainer.findByName(name) != null;
            }

            @Override
            public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
                if (arguments.length == 0) {
                    return DynamicInvokeResult.notFound();
                }
                Configuration configuration = configurationContainer.findByName(name);
                if (configuration == null) {
                    return DynamicInvokeResult.notFound(getAdditionalContext(DefaultJvmComponentDependencies.this, name, arguments));
                }

                List<?> normalizedArgs = CollectionUtils.flattenCollections(arguments);
                if (normalizedArgs.size() == 2 && normalizedArgs.get(1) instanceof Closure) {
                    // adding a dependency with a closure, like:
                    // implementation("foo:bar:1.0") {
                    //    transitive = false
                    // }
                    System.out.println("looks like you're trying to configure " + name + ", do <this> instead.");
                    return DynamicInvokeResult.notFound();
                } else if (normalizedArgs.size() == 1) {
                    // adding a dependency, like:
                    // implementation("foo:bar:1.0")
                    System.out.println("looks like you're trying to configure " + name + ", do <this> instead.");
                    return DynamicInvokeResult.notFound();
                } else {
                    // adding multiple dependencies, like:
                    // implementation("foo:bar:1.0", "baz:bar:2.0", "foobar:foobar:3.0")
                    System.out.println("looks like you're trying to configure " + name + ", do <this> instead.");
                    return DynamicInvokeResult.notFound();
                }
            }
        };
    }
}
