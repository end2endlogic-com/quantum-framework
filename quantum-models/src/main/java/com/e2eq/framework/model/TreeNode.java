package com.e2eq.framework.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public  class TreeNode {
        public String key;
        public String label;
        public Map<String, Object> data;
        public String icon;
        public List<TreeNode> children = new ArrayList<>();
    }
