package com.smaliscope.testapp;

/** 对象图展开的演示目标：基本类型字段 + 字符串字段 + 引用字段。 */
public class Point {
    public int x;
    public int y;
    public String name;
    public Point next;

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
        this.name = "P";
        this.next = null;
    }

    public void move(int dx, int dy) {
        this.x = this.x + dx;
        this.y = this.y + dy;
    }
}
