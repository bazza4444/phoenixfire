package com.phoenixfire.installer;

import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

    public interface OnAppClickListener {
        void onAppClick(AppModel app);
    }

    private final List<AppModel> apps;
    private final OnAppClickListener listener;

    public AppAdapter(List<AppModel> apps, OnAppClickListener listener) {
        this.apps = apps;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppModel app = apps.get(position);
        holder.tvName.setText(app.getName());
        holder.tvVersion.setText("v" + app.getVersion());
        holder.tvDescription.setText(app.getDescription());
        holder.tvCategory.setText(app.getCategory());
        holder.btnSend.setOnClickListener(v -> listener.onAppClick(app));
        // Set a default icon based on category
        holder.ivIcon.setImageResource(R.drawable.ic_app_default);
    }

    @Override
    public int getItemCount() { return apps.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvName, tvVersion, tvDescription, tvCategory;
        Button btnSend;

        ViewHolder(View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvVersion = itemView.findViewById(R.id.tvVersion);
            tvDescription = itemView.findViewById(R.id.tvDescription);
            tvCategory = itemView.findViewById(R.id.tvCategory);
            btnSend = itemView.findViewById(R.id.btnSend);
        }
    }
}
