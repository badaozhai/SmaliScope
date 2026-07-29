package com.smaliscope.testapp;

/**
 * 断点/单步演示的核心类。每个方法覆盖一类 smali 指令，便于观察寄存器与数据流：
 *   compute  : const / mul-int / add-int / if-* / goto —— 算术、循环、分支
 *   arraySum : new-array / aput / aget —— 数组
 *   describe : new-instance / iput / iget / invoke-virtual —— 对象与字符串
 */
public class Calc {
    public int lastResult;
    public String label;
    public Point origin;

    public Calc() {
        this.lastResult = 0;
        this.label = "calc";
        this.origin = new Point(0, 0);
    }

    /** 循环累加 a*i，再按阈值走两条不同分支——CFG 走过路径着色的最佳演示。 */
    public int compute(int a, int b) {
        int sum = 0;
        int i = 0;
        while (i < b) {
            sum = sum + a * i;
            i = i + 1;
        }
        if (sum > 10) {
            sum = sum - 1;
        } else {
            sum = sum + 100;
        }
        this.lastResult = sum;
        return sum;
    }

    /** 数组写入再读出求和。 */
    public int arraySum(int n) {
        int[] arr = new int[n];
        int i = 0;
        while (i < n) {
            arr[i] = i * i;
            i = i + 1;
        }
        int total = 0;
        int j = 0;
        while (j < n) {
            total = total + arr[j];
            j = j + 1;
        }
        return total;
    }

    /** 构造对象、改字段、拼字符串——对象图与 String 显示的演示。 */
    public String describe(int x, int y) {
        Point p = new Point(x, y);
        p.move(2, 3);
        this.origin = p;
        String s = this.label + "@" + p.x + "," + p.y;
        return s;
    }
}
