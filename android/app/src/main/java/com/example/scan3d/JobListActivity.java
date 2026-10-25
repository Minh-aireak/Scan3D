package com.example.scan3d;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.scan3d.api.ApiClient;
import com.example.scan3d.model.Job;
import com.example.scan3d.util.AppPrefs;
import com.example.scan3d.util.UiUtils;

import java.util.ArrayList;
import java.util.List;

/** Danh sách các lần quét đã gửi lên server. */
public class JobListActivity extends AppCompatActivity {

    private final ArrayList<Job> jobs = new ArrayList<>();
    private ArrayAdapter<Job> adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_job_list);
        UiUtils.applySystemBarPadding(findViewById(R.id.main));

        tvEmpty = findViewById(R.id.tvEmpty);
        ListView lvJobs = findViewById(R.id.lvJobs);
        Button btnRefresh = findViewById(R.id.btnRefresh);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, jobs);
        lvJobs.setAdapter(adapter);
        lvJobs.setEmptyView(tvEmpty);
        lvJobs.setOnItemClickListener((parent, view, position, id) ->
                JobDetailActivity.open(this, jobs.get(position).id));

        btnRefresh.setOnClickListener(v -> loadJobs());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadJobs();
    }

    private void loadJobs() {
        tvEmpty.setText("Đang tải...");
        new ApiClient(AppPrefs.getServerUrl(this)).listJobs(new ApiClient.ResultCallback<List<Job>>() {
            @Override
            public void onSuccess(List<Job> result) {
                jobs.clear();
                jobs.addAll(result);
                adapter.notifyDataSetChanged();
                tvEmpty.setText("Chưa có mô hình nào");
            }

            @Override
            public void onError(String message) {
                jobs.clear();
                adapter.notifyDataSetChanged();
                tvEmpty.setText(message);
            }
        });
    }
}
