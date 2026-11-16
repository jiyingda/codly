package com.jiyingda.codly.context;

import com.alibaba.fastjson.JSONObject;

/** 无分词器依赖的保守估算；容量来自用户的按模型配置，未配置时明确使用默认值。 */
public final class ContextBudget {
    private JSONObject options=new JSONObject();
    private int used;
    public void configure(JSONObject value){options=value==null?new JSONObject():value;}
    public int capacity(String model){JSONObject models=options.getJSONObject("models");return models!=null&&models.containsKey(model)?models.getIntValue(model):value("windowTokens",32768);}
    public int reserve(String model){return Math.min(value("reserveTokens",4096),Math.max(1,capacity(model)/2));}
    public int limit(String model){return capacity(model)-reserve(model);}
    public int threshold(String model){return Math.min(limit(model),(int)(capacity(model)*0.8));}
    public boolean autoCompact(){return !Boolean.FALSE.equals(options.getBoolean("autoCompact"));}
    public int keepRecent(){return value("keepRecentMessages",4);}
    private int value(String key,int fallback){return options.containsKey(key)?options.getIntValue(key):fallback;}
    public void used(int value){used=value;}
    public int used(){return used;}
    public String describe(String model){return "上下文估算 "+used+" / "+capacity(model)+" tokens，输入剩余约 "+Math.max(0,limit(model)-used)+"，预留输出 "+reserve(model)+(options.containsKey("windowTokens")||options.getJSONObject("models")!=null&&options.getJSONObject("models").containsKey(model)?"（配置容量）":"（默认容量，可在 context.models 配置）");}
    public static int estimate(String text){long ascii=0,other=0;for(int i=0;i<text.length();i++)if(text.charAt(i)<128)ascii++;else other++;return (int)Math.min(Integer.MAX_VALUE,(ascii+2)/3+other);}
}
