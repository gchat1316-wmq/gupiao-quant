package com.quant.service.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedNode {
    private String title;
    private String summary;
    private String definition;
    @Builder.Default
    private List<ExtractedNode> children = new ArrayList<>();

    public static ExtractedNode node(String title, String summary, String definition, ExtractedNode... children) {
        ExtractedNode n = new ExtractedNode();
        n.title = title;
        n.summary = summary;
        n.definition = definition;
        n.children = new ArrayList<>();
        if (children != null) {
            for (ExtractedNode c : children) n.children.add(c);
        }
        return n;
    }

    public static ExtractedNode leaf(String title, String summary) {
        return node(title, summary, null);
    }
}
