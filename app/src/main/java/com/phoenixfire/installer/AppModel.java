package com.phoenixfire.installer;

public class AppModel {
    private String name;
    private String version;
    private String description;
    private String iconUrl;
    private String apkUrl;
    private String packageName;
    private String category;

    public AppModel(String name, String version, String description,
                    String iconUrl, String apkUrl, String packageName, String category) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.iconUrl = iconUrl;
        this.apkUrl = apkUrl;
        this.packageName = packageName;
        this.category = category;
    }

    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getDescription() { return description; }
    public String getIconUrl() { return iconUrl; }
    public String getApkUrl() { return apkUrl; }
    public String getPackageName() { return packageName; }
    public String getCategory() { return category; }
}
