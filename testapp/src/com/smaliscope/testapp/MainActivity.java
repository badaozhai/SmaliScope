package com.smaliscope.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 纯代码构建 UI，不依赖任何资源文件，方便手工 aapt2 打包。
 * runAll() 是断点主目标；onResume 也会调用它，因此可用
 *   adb shell am start -n com.smaliscope.testapp/.MainActivity
 * 反复触发断点，无需点屏幕。
 */
public class MainActivity extends Activity {
    private static final String TAG = "SmaliScope";

    private TextView out;
    private final Calc calc = new Calc();
    private int runCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);

        TextView title = new TextView(this);
        title.setText("SmaliScope 测试应用");
        title.setTextSize(22f);
        root.addView(title);

        Button run = new Button(this);
        run.setText("运行 compute()");
        run.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAll();
            }
        });
        root.addView(run);

        out = new TextView(this);
        out.setTextSize(16f);
        root.addView(out);

        setContentView(root);
        runAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        runAll();
    }

    /** 断点主目标：依次调用三个演示方法。 */
    private void runAll() {
        runCount = runCount + 1;
        int r1 = calc.compute(3, 4);
        int r2 = calc.arraySum(5);
        String r3 = calc.describe(7, 9);
        String msg = "#" + runCount + " compute=" + r1 + " arraySum=" + r2 + " describe=" + r3;
        Log.i(TAG, msg);
        if (out != null) {
            out.setText(msg);
        }
    }
}
