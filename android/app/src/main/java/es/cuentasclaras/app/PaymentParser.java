package es.cuentasclaras.app;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconoce en el texto de una notificación (banco, Google Wallet, SMS del banco…)
 * un pago con tarjeta y extrae el importe y el comercio. Java puro: se puede probar sin Android.
 */
final class PaymentParser {

    /** App de banco u otra app normal. */
    static final int NORMAL = 0;
    /** Monedero (Google Wallet…) o app que el usuario ha marcado como de confianza. */
    static final int TRUSTED = 1;
    /** SMS: solo se aceptan mensajes que hablen claramente de un pago con tarjeta. */
    static final int STRICT = 2;
    /** Banco marcado como de confianza: se aceptan avisos de confirmación/autorización. */
    static final int TRUSTED_BANK = 3;

    static final class Result {
        double amount;
        String merchant; // puede ser null
    }

    private static final String NUM =
            "(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)";
    static final Pattern AMOUNT = Pattern.compile(
            "(?:€|\\bEUR)\\s?-?" + NUM + "|-?" + NUM + "\\s?(?:€|EUR\\b|euros?\\b)",
            Pattern.CASE_INSENSITIVE);

    /** Palabras que indican que es un gasto. */
    private static final String[] POSITIVE = {
            "pago", "pagad", "pagaste", "compra", "cargo", "cobro", "cobrad", "operacion", "transaccion",
            "movimiento", "gasto", "has realizado", "bizum enviado", "has enviado", "tarjeta",
            "payment", "paid", "purchase", "spent", "card used", "transaction"
    };

    /** Para SMS: además del importe, tiene que hablar de tarjeta o de una compra. */
    private static final String[] STRICT_POSITIVE = {
            "tarjeta", "compra", "cargo", "pago con", "pago en", "operacion con", "operacion de"
    };

    /** Descartan siempre: ingresos, devoluciones, rechazos, retiradas de efectivo… */
    private static final String[] NEGATIVE_HARD = {
            "devolucion", "devuelto", "reembols", "has recibido", "te han enviado", "te ha enviado",
            "recibido un", "recibido una", "transferencia recibida", "te han ingresado", "ingreso de",
            "abono de", "nomina", "cashback", "rechaz", "deneg", "cancelad", "anulad", "fallid",
            "no se ha podido", "no ha podido", "no se ha realizado", "insuficiente",
            "codigo de verificacion", "codigo de seguridad", "codigo de confirmacion", "codigo de un solo uso",
            "contrasena", "otp", "refund", "received", "declined", "failed", "verification code",
            "retirada de efectivo", "reintegro", "cajero", "sacar dinero",
            "tu saldo", "su saldo", "saldo es", "saldo actual", "saldo de tu cuenta", "saldo de su cuenta", "saldo bajo",
            "transferencia", "recibo", "domiciliacion"
    };

    /** Descartan en apps normales y SMS (publicidad, peticiones de confirmación…). */
    private static final String[] NEGATIVE_SOFT = {
            "oferta", "promocion", "gana ", "ganar ", "sorteo", "prestamo", "financia", "descuento",
            "confirma la", "confirma tu", "confirma el", "confirmar la", "autoriza la", "autoriza tu",
            "autorizar la", "pendiente de autoriz", "verifica", "acepta la", "aprueba la", "¿"
    };

    /** Códigos de un solo uso: "tu código es 123456". */
    private static final Pattern OTP = Pattern.compile(
            "\\b(codigo|clave|pin)\\b.{0,80}?(?<![\\d.,*])\\d{4,8}(?![\\d.,])|(?<![\\d.,*])\\d{4,8}(?![\\d.,]).{0,20}\\b(codigo|clave)\\b");

    private static final Pattern LINK = Pattern.compile("https?:|www\\.|\\b[a-z0-9-]+\\.(com|es|net|info|link|ly|me|top|xyz|app)(/|\\b)");

    /** Comercio en mayúsculas cuando no va precedido de "en" (p. ej. "Movimiento: -15,30 € PRIMARK"). */
    private static final Pattern UPPER = Pattern.compile("\\b([A-ZÁÉÍÓÚÑ0-9&'*][A-ZÁÉÍÓÚÑ0-9&'*.\\-]{2,}(?:\\s+[A-ZÁÉÍÓÚÑ0-9&'.\\-]{2,}){0,3})\\b");
    private static final String[] NOT_MERCHANT = {"eur", "euros", "tarjeta", "compra", "pago", "cargo", "visa",
            "mastercard", "debito", "credito", "operacion", "movimiento", "importe", "saldo", "unicaja", "bbva",
            "santander", "caixabank", "sabadell", "openbank", "ing", "bankinter", "abanca", "kutxabank", "ibercaja",
            "cajamar", "revolut", "n26", "bizum", "sms", "nfc", "pin"};

    private static final Pattern MERCHANT = Pattern.compile(
            "(?:\\ben|\\bat|\\bin)\\s+([^\\n.,;:!()]{2,60}?)(?=\\s+(?:con|por|el|la|a las|desde|via|with|on|using|ha|se|confirmad[oa]|autorizad[oa]|realizad[oa]|aceptad[oa]|-)\\b|[.,;:!\\n(]|$)",
            Pattern.CASE_INSENSITIVE);

    static Result parse(String title, String body, int mode) {
        String t = title == null ? "" : title.trim();
        String b = body == null ? "" : body.trim();
        String all = t + "\n" + b;
        String low = norm(all);

        if (containsAny(low, NEGATIVE_HARD)) return null;
        if (OTP.matcher(low).find()) return null;
        if (mode == NORMAL || mode == STRICT) {
            if (containsAny(low, NEGATIVE_SOFT)) return null;
        }
        if (mode != TRUSTED && !containsAny(low, POSITIVE)) return null;
        if (mode == STRICT && !containsAny(low, STRICT_POSITIVE)) return null;
        // SMS con enlaces: los bancos no mandan enlaces en los avisos de compra (suele ser phishing)
        if (mode == STRICT && LINK.matcher(low).find()) return null;

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

    /** ¿Hay un importe en euros en el texto? (para la lista de avisos no apuntados) */
    static boolean hasAmount(String s) {
        return s != null && AMOUNT.matcher(s).find();
    }

    /** ¿Parece un aviso de pago aunque no se haya apuntado? (para guardar SMS en la lista) */
    static boolean looksLikePayment(String s) {
        return containsAny(norm(s), STRICT_POSITIVE);
    }

    static double parseAmount(String s) {
        s = s.replace(" ", "").replace(" ", "").replace(" ", "").replace("-", "").trim();
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
        Matcher u = UPPER.matcher(body == null ? "" : body);
        while (u.find()) {
            String cand = u.group(1).trim();
            String n = norm(cand);
            boolean bad = n.replaceAll("[^a-z]", "").length() < 3;
            for (String w : NOT_MERCHANT) if (n.equals(w) || n.startsWith(w + " ")) bad = true;
            if (!bad) {
                String c = clean(cand);
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
                || n.contains("••") || n.contains("**") || n.startsWith("tu ") || n.startsWith("su ")
                || n.contains(" tu ") || n.startsWith("el dia") || n.startsWith("fecha")
                || n.startsWith("comercio")) return null;
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
