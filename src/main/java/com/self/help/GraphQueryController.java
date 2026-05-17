package com.self.help;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/graph")
public class GraphQueryController {
    private final GraphQueryService graphQueryService;

    public GraphQueryController(GraphQueryService graphQueryService) {
        this.graphQueryService = graphQueryService;
    }

    @GetMapping("/vertices")
    public Map<String, String> getVertices() {
        return graphQueryService.getVertices();
    }

    @GetMapping("/edges")
    public List<String> getEdges() {
        return graphQueryService.getEdges();
    }
}
