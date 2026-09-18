package com.eternal.asharewidget;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("轻行情 Widget");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("桌面常驻行情小部件。\n\n"
                + "数据源：\n"
                + "• 沪深北 A 股与大陆指数：腾讯财经\n"
                + "• COMEX 期货、纳斯达克指数、外汇：新浪财经\n\n"
                + "推荐代码：\n"
                + "• sh603605 / sh600584 / sh600141 / sh000688\n"
                + "• hf_GC（COMEX 黄金）\n"
                + "• gb_ixic（纳斯达克综合指数）\n"
                + "• fx_scnyrub（人民币/卢布）\n\n"
                + "旧 Yahoo 写法 GC=F、^IXIC、CNYRUB=X 仍可输入，程序会自动转换为新浪代码。\n\n"
                + "每行显示名称、迷你折线、现价、涨跌额、涨跌幅；桌面不显示股票代码，也不显示“自选股”标题。\n\n"
                + "折线可选择 5 / 20 / 60 / 120 / 250 日。默认 20 日。\n\n"
                + "价格提醒：点右上角 ⚙，可新增、修改、删除 >= 或 <= 目标价提醒。"
                + "提醒检查频率与自动刷新相同。\n\n"
                + "中国市场习惯：红涨、绿跌。Android / HyperOS 的后台省电策略可能让定时刷新和价格提醒延后。");
        body.setTextSize(15);
        body.setLineSpacing(0, 1.2f);
        body.setPadding(0, pad / 2, 0, 0);
        root.addView(body);
        setContentView(root);
    }
}
