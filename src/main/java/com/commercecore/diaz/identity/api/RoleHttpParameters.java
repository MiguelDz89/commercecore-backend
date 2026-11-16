package com.commercecore.diaz.identity.api;
import java.util.UUID;
final class RoleHttpParameters {
    private RoleHttpParameters() { }
    static UUID id(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid identifier"); }
    }
    static int number(String value, int min, int max) {
        try {
            int n=Integer.parseInt(value);
            if(n<min || n>max) throw new IllegalArgumentException("Invalid pagination");
            return n;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid pagination"); }
    }
}
