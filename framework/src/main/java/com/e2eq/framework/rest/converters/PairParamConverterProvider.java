package com.e2eq.framework.rest.converters;

import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@Provider
public class PairParamConverterProvider implements ParamConverterProvider {

    @Override
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        if (rawType.equals(Pair[].class)) {
            return new PairArrayParamConverter<>();
        }
        return null;
    }

    public static class PairArrayParamConverter<T> implements ParamConverter<T> {
        @Override
        public T fromString(String value) {
            // Implement your logic to convert the string value to Pair[]
            // Example: value = "key1:value1,key2:value2,key3:value3"

            // remove the first and last characters
            value = value.substring(1, value.length() - 1);

            String[] pairs = value.split(",");
            List<Pair> pairList = new ArrayList<>();
            for (String pairString : pairs) {
                String[] keyValue = pairString.split(":");
                if (keyValue.length == 2) {
                    pairList.add( Pair.of(keyValue[0], keyValue[1]));
                }
            }
            return (T) pairList.toArray(new Pair[0]);
        }

        @Override
        public String toString(T value) {
            // Implement your logic to convert Pair[] to a string
            StringBuilder stringBuilder = new StringBuilder();
            for (Pair pair : (Pair[]) value) {
                stringBuilder.append(pair.getKey()).append(":").append(pair.getValue()).append(",");
            }
            // Remove the trailing comma
            if (stringBuilder.length() > 0) {
                stringBuilder.setLength(stringBuilder.length() - 1);
            }
            return stringBuilder.toString();
        }
    }

}
