/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.stuartmathews.endofday;

import java.lang.reflect.Field;

import java.lang.reflect.Modifier;

import java.util.ArrayList;

import java.util.List;



import org.apache.log4j.Logger;



public class ObjectListToCSV {



private static final Logger logger = Logger.getLogger(ObjectListToCSV.class);

private static final String CSV_SEPARATOR = ",";



public static <T>  String convertListToCSV(List<T> objectList) {
  if(objectList.size() < 1) {
   logger.info("No data in the list to convert to CDR!");
   return "";
  }
  String csv = "";
  T t = objectList.get(0);
  Field[] declaredFields = t.getClass().getDeclaredFields();
  ArrayList useableFields = getUseableFields(declaredFields);
  csv = getCSVHeader(csv, useableFields,null);
  csv=csv.concat("\n");
  for(T object : objectList) {
   csv = addObjectValue(csv, useableFields, object);
   csv=csv.concat("\n");
  }
  return csv;
 }

 private static<T>  String addObjectValue(String csv,
   ArrayList<Field> useableFields, T object) {
  for(Field field : useableFields) {
   try {
    if(canFieldUsedDirectly(field)) {
     if(object != null) {
      field.setAccessible(true);
      Object value = field.get(object);
      csv=csv.concat(value +"");
      field.setAccessible(false);
     }
    } else {
     if(object != null) {
      field.setAccessible(true);
      Object value = field.get(object);
      field.setAccessible(false);
      csv = addObjectValue(csv, getUseableFields(field.getType().getDeclaredFields()), value);
     }
    }
   } catch (IllegalArgumentException e) {
    logger.error(e);
   } catch (SecurityException e) {
    logger.error(e);
   } catch (IllegalAccessException e) {
    logger.error(e);
   }
   csv=csv.concat(CSV_SEPARATOR);
  }
  return csv;
 }

 private static boolean canFieldUsedDirectly(Field field) {
  return !field.getType().toString().contains("biplav");
 }

 private static ArrayList<Field> getUseableFields(Field[] declaredFields) {
  ArrayList useableFields = new ArrayList();
  for(Field field: declaredFields) {
   if(!Modifier.isStatic(field.getModifiers())) {
     useableFields.add(field);
   }
  }
  return useableFields;
 }

 private static String getCSVHeader(String csv, ArrayList<Field> useableFields,String prefix) {
  prefix = (prefix == null) ?  "": prefix;
  for(Field field : useableFields) {
   if(canFieldUsedDirectly(field)) {
    csv=csv.concat(prefix+field.getName());
    csv=csv.concat(CSV_SEPARATOR);
   } else {
    csv = getCSVHeader(csv, getUseableFields(field.getType().getDeclaredFields()),field.getName()+"_");
   }
  }
  return csv;
 }
}