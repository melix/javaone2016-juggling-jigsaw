package com.acme.utils.internal;
import org.apache.commons.lang3.StringUtils;


public class DarkUtils {
   public static String pretty(String str) {
      return StringUtils.capitalize(str) +
         "change some internal details";
   }
}
