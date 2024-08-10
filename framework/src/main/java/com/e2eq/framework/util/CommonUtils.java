package com.e2eq.framework.util;

import com.google.common.collect.Lists;
import io.quarkus.logging.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class CommonUtils {


    /**
     * Take a list of items and create all the possible permutations of it
     *
     * Example
     * java.util.List<java.util.Collection<String>> lists = new LinkedList<java.util.Collection<String>>();
     * lists.add(m1.getMatrixValues()); // color
     * lists.add(m2.getMatrixValues()); // size
     * lists.add(m3.getMatrixValues()); // style

     * List<String> uomsetStringList = new ArrayList<>();
     * for UnitOfMeasure u : uoms.getUnitOfMeasurements())
     * {
     *    uomsetStringList.add(u.getAbbreviation());
     * }
     *
     * lists.add(uomsetStringList);
     *
     * java.util.Collection<List<String>> permutations =  this.permutations(lists);
     *
     * @param collections the collections you want to calculate the permuations on
     * @param <T> the type of the element in the collection
     * @return the resulting permuations
     */
    public static <T> java.util.Collection<List<T>> permutations(List<java.util.Collection<T>> collections) {
        if (collections == null || collections.isEmpty()) {
            return Collections.emptyList();
        } else {
            java.util.Collection<List<T>> res = Lists.newLinkedList();
            permutationsImpl(collections, res, 0, new LinkedList<T>());
            return res;
        }
    }

    /** Recursive implementation for*/
    private static <T> void permutationsImpl(List<java.util.Collection<T>> ori, java.util.Collection<List<T>> res, int
            d, List<T>
                                                     current) {
        // if depth equals number of original collections, final reached, add and return
        if (d == ori.size()) {
            res.add(current);
            return;
        }

        // iterate from current collection and copy 'current' element N times, one for each element
        java.util.Collection<T> currentCollection = ori.get(d);
        for (T element : currentCollection) {
            List<T> copy = Lists.newLinkedList(current);
            copy.add(element);
            permutationsImpl(ori, res, d + 1, copy);
        }
    }

    public static void closeStreams(Closeable... streams) {
        if(streams != null) {
            for(Closeable stream : streams) {
                try {
                    stream.close();
                } catch (IOException e) {
                    Log.warn("Failed to close stream");
                }
            }
        }
    }

    public static double calcDistance(double latA, double longA, double latB, double longB) {
        double theDistance = (Math.sin(Math.toRadians(latA)) *
                Math.sin(Math.toRadians(latB)) +
                Math.cos(Math.toRadians(latA)) *
                        Math.cos(Math.toRadians(latB)) *
                        Math.cos(Math.toRadians(longA - longB)));

        return (Math.toDegrees(Math.acos(theDistance))) * 69.09;
    }

    public static String emailDomainFromCompanyName(String companyName) {

        if (companyName == null) {
            throw new IllegalArgumentException("companyName can not be null");
        }

        String inputString = companyName;

        inputString = inputString.replaceAll("[^a-zA-Z0-9]+","");
        if(inputString.length() > 25) {
            inputString = inputString.substring(0,25);
        }

        inputString = inputString.toLowerCase()+".com";
        return inputString;
    }

    /**
     * Will camel case a given string that contains spaces so for example
     * "This Is a example" will turn into "thisIsAExample"
     *
     * @param inputString - input string which is assumed to contain spaces.
     * @return the camel cased version of the string
     */
    public static String camelCaseString(String inputString) {
        if (inputString == null) {
            return null;
        } else if (inputString.isEmpty()) {
            return inputString;
        }

        StringBuilder sb = new StringBuilder();
        for (String oneString : inputString.split("[ ]")) {
            if(oneString.length() > 1) {
                sb.append(oneString.substring(0, 1).toUpperCase());
                sb.append(oneString.substring(1));
            }
        }

        return sb.toString();

    }

    public static String calculateMD5(String input) {

        if (input == null) {
            return null;
        }

        if (input.isEmpty()) {
            return null;
        }

        MessageDigest messageDigest = null;
        try {
            messageDigest = MessageDigest.getInstance("MD5");

            byte[] array = messageDigest.digest(input.getBytes("UTF-8"));

            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < array.length; ++i) {
                sb.append(Integer.toHexString((array[i] & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        throw new IllegalStateException("MD5 calculation failed");
    }


}