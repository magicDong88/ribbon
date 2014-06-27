/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.ribbonclientextensions.typedclient;

import com.netflix.ribbonclientextensions.CacheProvider;
import com.netflix.ribbonclientextensions.CacheProviderFactory;
import com.netflix.ribbonclientextensions.RibbonRequest;
import com.netflix.ribbonclientextensions.http.HttpResponseValidator;
import com.netflix.ribbonclientextensions.hystrix.FallbackHandler;
import com.netflix.ribbonclientextensions.typedclient.annotation.CacheProviders;
import com.netflix.ribbonclientextensions.typedclient.annotation.CacheProviders.Provider;
import com.netflix.ribbonclientextensions.typedclient.annotation.Content;
import com.netflix.ribbonclientextensions.typedclient.annotation.ContentTransformerClass;
import com.netflix.ribbonclientextensions.typedclient.annotation.Http;
import com.netflix.ribbonclientextensions.typedclient.annotation.Http.Header;
import com.netflix.ribbonclientextensions.typedclient.annotation.Http.HttpMethod;
import com.netflix.ribbonclientextensions.typedclient.annotation.Hystrix;
import com.netflix.ribbonclientextensions.typedclient.annotation.TemplateName;
import com.netflix.ribbonclientextensions.typedclient.annotation.Var;
import io.reactivex.netty.serialization.ContentTransformer;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.String.*;

/**
 * Extracts information from Ribbon annotated method, to automatically populate the Ribbon request template.
 * A few validations are performed as well:
 * - a return type must be {@link com.netflix.ribbonclientextensions.RibbonRequest}
 * - HTTP method must be always specified explicitly (there are no defaults)
 * - only one parameter with {@link com.netflix.ribbonclientextensions.typedclient.annotation.Content} annotation is allowed
 *
 * @author Tomasz Bak
 */
public class MethodTemplate {
    private final Method method;
    private final String templateName;
    private final Http.HttpMethod httpMethod;
    private final String path;
    private final Map<String, String> headers;
    private final String[] paramNames;
    private final int[] valueIdxs;
    private final int contentArgPosition;
    private final Class<? extends ContentTransformer<?>> contentTansformerClass;
    private final Class<?> resultType;
    private final String hystrixCacheKey;
    private final FallbackHandler<?> hystrixFallbackHandler;
    private final HttpResponseValidator hystrixResponseValidator;
    private final Map<String, CacheProvider<?>> cacheProviders;

    public MethodTemplate(Method method) {
        this.method = method;
        MethodAnnotationValues values = new MethodAnnotationValues(method);
        templateName = values.templateName;
        httpMethod = values.httpMethod;
        path = values.path;
        headers = Collections.unmodifiableMap(values.headers);
        paramNames = values.paramNames;
        valueIdxs = values.valueIdxs;
        contentArgPosition = values.contentArgPosition;
        contentTansformerClass = values.contentTansformerClass;
        resultType = values.resultType;
        hystrixCacheKey = values.hystrixCacheKey;
        hystrixFallbackHandler = values.hystrixFallbackHandler;
        hystrixResponseValidator = values.hystrixResponseValidator;
        cacheProviders = Collections.unmodifiableMap(values.cacheProviders);
    }

    public HttpMethod getHttpMethod() {
        return httpMethod;
    }

    public String getTemplateName() {
        return templateName;
    }

    public Method getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getParamName(int idx) {
        return paramNames[idx];
    }

    public int getParamPosition(int idx) {
        return valueIdxs[idx];
    }

    public int getParamSize() {
        return paramNames.length;
    }

    public int getContentArgPosition() {
        return contentArgPosition;
    }

    public Class<? extends ContentTransformer<?>> getContentTransformerClass() {
        return contentTansformerClass;
    }

    public Class<?> getResultType() {
        return resultType;
    }

    public String getHystrixCacheKey() {
        return hystrixCacheKey;
    }

    public FallbackHandler<?> getHystrixFallbackHandler() {
        return hystrixFallbackHandler;
    }

    public HttpResponseValidator getHystrixResponseValidator() {
        return hystrixResponseValidator;
    }

    public Map<String, CacheProvider<?>> getCacheProviders() {
        return cacheProviders;
    }

    public static <T> MethodTemplate[] from(Class<T> clientInterface) {
        List<MethodTemplate> list = new ArrayList<MethodTemplate>(clientInterface.getMethods().length);
        for (Method m : clientInterface.getMethods()) {
            list.add(new MethodTemplate(m));
        }
        return list.toArray(new MethodTemplate[list.size()]);
    }

    private static class MethodAnnotationValues {

        private final Method method;
        private String templateName;
        private Http.HttpMethod httpMethod;
        private String path;
        private String[] paramNames;
        private int[] valueIdxs;
        private int contentArgPosition;
        private Class<? extends ContentTransformer<?>> contentTansformerClass;
        private Class<?> resultType;
        public String hystrixCacheKey;
        private FallbackHandler<?> hystrixFallbackHandler;
        private HttpResponseValidator hystrixResponseValidator;
        public final Map<String, String> headers = new HashMap<String, String>();
        private final Map<String, CacheProvider<?>> cacheProviders = new HashMap<String, CacheProvider<?>>();

        private MethodAnnotationValues(Method method) {
            this.method = method;
            extractTemplateName();
            extractHttpAnnotation();
            extractParamNamesWithIndexes();
            extractContentArgPosition();
            extractContentTransformerClass();
            extractResultType();
            extractHystrixHandlers();
            extractCacheProviders();
        }

        private void extractCacheProviders() {
            CacheProviders annotation = method.getAnnotation(CacheProviders.class);
            if (annotation != null) {
                for (Provider provider : annotation.value()) {
                    Class<? extends CacheProviderFactory<?>> providerClass = provider.provider();
                    CacheProviderFactory<?> factory = Utils.newInstance(providerClass);
                    cacheProviders.put(provider.key(), factory.createCacheProvider());
                }
            }
        }

        private void extractHystrixHandlers() {
            Hystrix annotation = method.getAnnotation(Hystrix.class);
            if (annotation == null) {
                return;
            }
            String cacheKey = annotation.cacheKey().trim();
            if (!cacheKey.isEmpty()) {
                hystrixCacheKey = cacheKey;
            }
            if (!Hystrix.UndefFallbackHandler.class.equals(annotation.fallbackHandler())) {
                hystrixFallbackHandler = Utils.newInstance(annotation.fallbackHandler());
            }
            if (!Hystrix.UndefHttpResponseValidator.class.equals(annotation.validator())) {
                hystrixResponseValidator = Utils.newInstance(annotation.validator());
            }
        }

        private void extractHttpAnnotation() {
            Http annotation = method.getAnnotation(Http.class);
            if (null == annotation) {
                throw new IllegalArgumentException(format(
                        "Method %s.%s misses @Http annotation",
                        method.getDeclaringClass().getSimpleName(), method.getName()));
            }
            httpMethod = annotation.method();
            path = annotation.path();
            for (Header h : annotation.headers()) {
                headers.put(h.name(), h.value());
            }
        }

        private void extractParamNamesWithIndexes() {
            List<String> nameList = new ArrayList<String>();
            List<Integer> idxList = new ArrayList<Integer>();
            Annotation[][] params = method.getParameterAnnotations();
            for (int i = 0; i < params.length; i++) {
                for (Annotation a : params[i]) {
                    if (a.annotationType().equals(Var.class)) {
                        String name = ((Var) a).value();
                        nameList.add(name);
                        idxList.add(i);
                    }
                }
            }
            int size = nameList.size();
            paramNames = new String[size];
            valueIdxs = new int[size];
            for (int i = 0; i < size; i++) {
                paramNames[i] = nameList.get(i);
                valueIdxs[i] = idxList.get(i);
            }
        }

        private void extractContentArgPosition() {
            Annotation[][] params = method.getParameterAnnotations();
            int pos = -1;
            int count = 0;
            for (int i = 0; i < params.length; i++) {
                for (Annotation a : params[i]) {
                    if (a.annotationType().equals(Content.class)) {
                        pos = i;
                        count++;
                    }
                }
            }
            if (count > 1) {
                throw new IllegalArgumentException(format(
                        "Method %s.%s annotates multiple parameters as @Content - at most one is allowed ",
                        method.getDeclaringClass().getSimpleName(), method.getName()));
            }
            contentArgPosition = pos;
        }

        private void extractTemplateName() {
            TemplateName annotation = method.getAnnotation(TemplateName.class);
            if (null != annotation) {
                templateName = annotation.value();
            }
        }

        private void extractContentTransformerClass() {
            ContentTransformerClass annotation = method.getAnnotation(ContentTransformerClass.class);
            if (null != annotation) {
                contentTansformerClass = annotation.value();
            }
        }

        private void extractResultType() {
            Class<?> returnClass = method.getReturnType();
            if (!returnClass.isAssignableFrom(RibbonRequest.class)) {
                throw new IllegalArgumentException(format(
                        "Method %s.%s must return Void or RibbonRequest<T> type not %s",
                        method.getDeclaringClass().getSimpleName(), method.getName(), returnClass.getSimpleName()));
            }
            ParameterizedType returnType = (ParameterizedType) method.getGenericReturnType();
            resultType = (Class<?>) returnType.getActualTypeArguments()[0];
        }
    }
}
