package com.stuartmathews.endofday;

import java.util.List;

public class ResultSet   
{
    private String Query;
    public String getQuery() {
        return Query;
    }

    public void setQuery(String value) {
        Query = value;
    }

    private List<Result> Result;
    public List<Result> getResult() {
        return Result;
    }

    public void setResult(List<Result> value) {
        Result = value;
    }

}


