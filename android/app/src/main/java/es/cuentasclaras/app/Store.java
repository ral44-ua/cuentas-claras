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

    /** Evita apuntar dos veces el mismo pago (banco y Wallet avisan a la vez). */
    static synchronized boolean isDuplicate(Context c, double amount, long now) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("recent", "[]"));
            JSONArray keep = new JSONArray();
            boolean dup = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                long t = o.optLong("t");
                if (now - t > 10 * 60 * 1000) continue;
                keep.put(o);
                if (Math.abs(o.optDouble("amt") - amount) < 0.005 && now - t < 3 * 60 * 1000) dup = true;
            }
            if (!dup) keep.put(new JSONObject().put("amt", amount).put("t", now));
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
