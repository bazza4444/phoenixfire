package com.phoenixfire.installer;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class AppListParser {

    public static List<AppModel> loadApps(Context context) {
        List<AppModel> apps = new ArrayList<>();
        try {
            InputStream is = context.getAssets().open("app_list.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();

            String json = new String(buffer, "UTF-8");
            JSONObject root = new JSONObject(json);
            JSONArray appsArray = root.getJSONArray("apps");

            for (int i = 0; i < appsArray.length(); i++) {
                JSONObject obj = appsArray.getJSONObject(i);
                apps.add(new AppModel(
                    obj.optString("name", "Unknown"),
                    obj.optString("version", ""),
                    obj.optString("description", ""),
                    obj.optString("icon_url", ""),
                    obj.optString("apk_url", ""),
                    obj.optString("package_name", ""),
                    obj.optString("category", "General")
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return apps;
    }
}
