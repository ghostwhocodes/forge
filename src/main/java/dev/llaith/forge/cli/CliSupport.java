package dev.llaith.forge.cli;

import dev.llaith.forge.ForgeException;

import java.util.List;

public final class CliSupport {
    private CliSupport() {
    }

    public static String optionValue(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    public static KeyValue parseKeyValue(String value, String kind) {
        int separator = value.indexOf('=');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new ForgeException("error: invalid " + kind + ", expected KEY=VALUE");
        }
        return new KeyValue(value.substring(0, separator), value.substring(separator + 1));
    }

    public static boolean parseBooleanFlag(String value, String kind) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new ForgeException("error: invalid " + kind + ", expected true or false");
        };
    }

    public static ForgeException missingOption(String option) {
        return new ForgeException("error: missing " + option);
    }

    public static String requireValue(String value, String option) {
        if (value == null) {
            throw missingOption(option);
        }
        return value;
    }

    public static String requireNonBlank(String value, String option) {
        if (value == null || value.trim().isEmpty()) {
            throw missingOption(option);
        }
        return value.trim();
    }

    public static ForgeException unknownOption(String option) {
        return new ForgeException("error: unknown option: " + option);
    }

    public static boolean isHelpFlag(String value) {
        return "-h".equals(value) || "--help".equals(value);
    }

    public static boolean containsHelp(List<String> args) {
        return args.stream().anyMatch(CliSupport::isHelpFlag);
    }

    public record KeyValue(String key, String value) {
    }
}
