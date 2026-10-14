package es.cuentasclaras.app;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconoce en el texto de una notificación (banco, Google Wallet, Samsung Wallet…)
 * un pago con tarjeta y extrae el importe y el comercio. Java puro: se puede probar sin Android.
 */
final class PaymentParser {

    static final class Result {
        double amount;
        String merchant; // puede ser null
    }

    private static final String NUM =
            "(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)";
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:€|\\bEUR)\\s?" + NUM + "|" + NUM + "\\s?(?:€|EUR\\b|euros?\\b)",
            Pattern.CASE_INSENSITIVE);

    /** Palabras que indican que es un gasto. */
    private static final String[] POSITIVE = {
            "pago", "pagad", "pagaste", "has pagado", "compra", "cargo", "cobro", "cobrad",
            "operacion con", "operacion de", "transaccion", "bizum enviado", "has enviado",
            "payment", "paid", "purchase", "spent", "you spent", "card used", "tarjeta"
    };

    /** Palabras que descartan la notificación: ingresos, devoluciones, rechazos, códigos, publicidad… */
    private static final String[] NEGATIVE = {
            "devolucion", "reembols", "recibid", "has recibido", "ingreso", "abono", "cashback",
            "rechaz", "deneg", "cancelad", "anulad", "fallid", "no se ha podido", "no ha podido",
            "insuficiente", "codigo", "clave", "contrasena", "verifica", "autoriza", "confirma",
            "oferta", "promocion", "descuento", "gana ", "sorteo", "prestamo", "financia",
            "refund", "received", "declined", "failed", "verification", "otp",
            "reintegro", "retirada de efectivo", "cajero"
    };

    private static final Pattern MERCHANT = Pattern.compile(
            "(?:\\ben|\\bat|\\bin)\\s+([^\\n.,;:!()]{2,60}?)(?=\\s+(?:con|por|el|a las|desde|via|with|on|using|-)\\b|[.,;:!\\n(]|$)",
            Pattern.CASE_INSENSITIVE);

    static Result parse(String title, String body, boolean trustedWallet) {
        String t = title == null ? "" : title.trim();
        String b = body == null ? "" : body.trim();
        String all = t + "\n" + b;
        String low = norm(all);

        if (!trustedWallet && !containsAny(low, POSITIVE)) return null;
        if (containsAny(low, NEGATIVE)) return null;

        Matcher m = AMOUNT.matcher(all);
        if (!m.find()) return null;
        String raw = m.group(1) != null ? m.group(1) : m.group(2);
        double amount;
        try {
            amount = parseAmount(raw);
        } catch (Exception e) {
            return null;
        }
        if (!(amount > 0 && amount < 100000)) return null;

        Result r = new Result();
        r.amount = Math.round(amount * 100) / 100.0;
        r.merchant = merchant(t, b);
        return r;
    }

    static double parseAmount(String s) {
        s = s.replace(" ", "").replace(" ", "").replace(" ", "").trim();
        int c = s.lastIndexOf(','), d = s.lastIndexOf('.');
        if (c >= 0 && d >= 0) {
            if (c > d) s = s.replace(".", "").replace(',', '.');
            else s = s.replace(",", "");
        } else if (c >= 0) {
            s = s.replace(',', '.');
        } else if (d >= 0) {
            String after = s.substring(d + 1);
            if (after.length() == 3) s = s.replace(".", "");
        }
        return Double.parseDouble(s);
    }

    static String merchant(String title, String body) {
        for (String src : new String[]{body, title}) {
            Matcher m = MERCHANT.matcher(src);
            while (m.find()) {
                String c = clean(m.group(1));
                if (c != null) return c;
            }
        }
        // Google Wallet y similares: el título es el comercio y el texto el importe
        String t = clean(title);
        if (t != null && !AMOUNT.matcher(title).find() && !containsAny(norm(title), POSITIVE)) return t;
        return null;
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() < 2) return null;
        String n = norm(s);
        if (n.matches(".*\\d{3,}.*")) return null;              // números de tarjeta, referencias
        if (n.contains("tarjeta") || n.contains("cuenta") || n.contains("visa") || n.contains("mastercard")
                || n.contains("••") || n.contains("**") || n.contains("tu ") || n.contains("su ")
                || n.startsWith("el dia") || n.startsWith("fecha")) return null;
        if (AMOUNT.matcher(s).find()) return null;
        if (s.length() > 40) s = s.substring(0, 40).trim();
        return s;
    }

    static String norm(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String low, String[] words) {
        for (String w : words) if (low.contains(w)) return true;
        return false;
    }
}
