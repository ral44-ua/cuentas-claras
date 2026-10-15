package es.cuentasclaras.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Almacén compartido entre el servicio que detecta pagos y la app:
 * - "pending": pagos detectados que la app todavía no ha añadido.
 * - "summary": resumen del mes que envía la app (presupuesto, gastado, umbrales) para
 *   poder avisar del límite aunque la app esté cerrada.
 */
final class Store {
    private static final String PREFS = "cuentas_auto";

    private Store() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized boolean autoEnabled(Context c) {
        return sp(c).getBoolean("auto", false);
    }

    static synchronized void addPending(Context c, JSONObject p) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("pending", "[]"));
            a.put(p);
            sp(c).edit().putString("pending", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    static synchronized String takePending(Context c) {
        String s = sp(c).getString("pending", "[]");
        sp(c).edit().putString("pending", "[]").apply();
        return s;
    }

    private static double pendingSum(Context c) {
        double sum = 0;
        try {
            JSONArray a = new JSONArray(sp(c).getString("pending", "[]"));
            for (int i = 0; i < a.length(); i++) sum += a.getJSONObject(i).optDouble("amt", 0);
        } catch (Exception ignored) { }
        return sum;
    }

    /** Apps que el usuario ha marcado como de confianza (sus avisos con importe se apuntan siempre). */
    static synchronized boolean isTrusted(Context c, String pkg) {
        try {
            JSONObject s = new JSONObject(sp(c).getString("summary", "{}"));
            JSONArray t = s.optJSONArray("trusted");
            if (t == null) return false;
            for (int i = 0; i < t.length(); i++) if (pkg.equals(t.optString(i))) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** Guarda (solo en el móvil) los últimos avisos con importe que no se han apuntado. */
    static synchronized void addSkipped(Context c, JSONObject o) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("skipped", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                if (x.optString("text").equals(o.optString("text"))
                        && x.optString("title").equals(o.optString("title"))) return; // aviso repetido
            }
            JSONArray out = new JSONArray();
            int start = Math.max(0, a.length() - 14);
            for (int i = start; i < a.length(); i++) out.put(a.get(i));
            out.put(o);
            sp(c).edit().putString("skipped", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    static synchronized String getSkipped(Context c) {
        return sp(c).getString("skipped", "[]");
    }

    static synchronized void removeSkipped(Context c, String id) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("skipped", "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!id.equals(o.optString("id")) && !"*".equals(id)) out.put(o);
            }
            sp(c).edit().putString("skipped", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    /**
     * Evita apuntar dos veces el mismo pago:
     * - la misma notificación que se actualiza (misma clave e importe);
     * - el mismo pago avisado por dos apps (Google Wallet al momento y el banco con retraso):
     *   un aviso de otra app con el mismo importe en las 12 horas anteriores que aún no se ha
     *   emparejado se considera el mismo pago. Cada aviso se empareja una sola vez, así que dos
     *   cafés iguales pagados con el móvil siguen contando como dos.
     */
    static synchronized boolean isDuplicate(Context c, double amount, long now, String pkg, String key) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("recent", "[]"));
            JSONArray keep = new JSONArray();
            int match = -1;
            boolean dup = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                long t = o.optLong("t");
                if (now - t > 12L * 60 * 60 * 1000) continue;
                keep.put(o);
                boolean same = Math.abs(o.optDouble("amt") - amount) < 0.005;
                if (!same || dup) continue;
                if (key != null && key.equals(o.optString("key"))) {
                    dup = true; // la misma notificación actualizada
                } else if (!pkg.equals(o.optString("pkg")) && !o.optBoolean("paired") && match < 0) {
                    match = keep.length() - 1;
                }
            }
            if (!dup && match >= 0) {
                keep.getJSONObject(match).put("paired", true);
                dup = true;
            }
            if (!dup) {
                keep.put(new JSONObject().put("amt", amount).put("t", now).put("pkg", pkg)
                        .put("key", key == null ? "" : key));
            }
            sp(c).edit().putString("recent", keep.toString()).apply();
            return dup;
        } catch (Exception e) {
            return false;
        }
    }

    /** La app envía su resumen del mes cada vez que se actualiza. */
    static synchronized void syncSummary(Context c, String json) {
        try {
            JSONObject s = new JSONObject(json);
            s.put("spent", s.optDouble("spent", 0) + pendingSum(c));
            sp(c).edit()
                    .putString("summary", s.toString())
                    .putBoolean("auto", s.optBoolean("auto", false))
                    .apply();
        } catch (Exception ignored) { }
    }

    /**
     * Suma un pago al resumen y devuelve el umbral (%) recién alcanzado del que hay que avisar,
     * o 0 si no hay que avisar. Devuelve también los datos para el texto del aviso.
     */
    static synchronized double[] addToSummary(Context c, double amount, String ym) {
        try {
            JSONObject s = new JSONObject(sp(c).getString("summary", "{}"));
            double budget = s.optDouble("budget", 0);
            double spent = s.optDouble("spent", 0);
            int ack = s.optInt("ack", 0);
            if (!ym.equals(s.optString("ym"))) {
                // Mes nuevo: empezamos con los gastos fijos
                spent = s.optDouble("fixed", 0);
                ack = 0;
                s.put("ym", ym);
                s.put("ack", 0);
            }
            double before = spent, after = spent + amount;
            s.put("spent", after);
            int lb = reached(s, before, budget), la = reached(s, after, budget);
            String key = "notified_" + ym;
            int notified = sp(c).getInt(key, 0);
            SharedPreferences.Editor ed = sp(c).edit().putString("summary", s.toString());
            int level = 0;
            if (la > lb && la > Math.max(notified, ack)) {
                level = la;
                ed.putInt(key, la);
            }
            ed.apply();
            return new double[]{level, after, budget};
        } catch (Exception e) {
            return new double[]{0, 0, 0};
        }
    }

    private static int reached(JSONObject s, double spent, double budget) {
        if (budget <= 0) return 0;
        double p = spent / budget * 100;
        JSONArray th = s.optJSONArray("th");
        int r = 0;
        if (th == null) return p >= 100 ? 100 : (p >= 80 ? 80 : 0);
        for (int i = 0; i < th.length(); i++) {
            int t = th.optInt(i);
            if (p >= t && t > r) r = t;
        }
        return r;
    }
}
