package com.studyagent.learning;

import static com.studyagent.learning.PlanningData.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class PlanningOutline {
    private PlanningOutline() { }

    static Result build(Outline outline, Emphasis emphasis, List<Task> tasks) {
        Map<Long, Point> points = outline.chapters().stream().flatMap(c -> c.points().stream())
                .collect(Collectors.toMap(Point::id, Function.identity()));
        Branch root = new Branch(0L, "", "NORMAL");
        for (Task task : tasks) {
            Branch parent = root;
            for (String title : points.get(task.knowledgePointId()).path()) {
                Branch child = parent.groups.get(title);
                if (child == null) {
                    child = new Branch(IdWorker.getId(), title, "NORMAL");
                    parent.groups.put(title, child);
                    parent.children.add(child);
                }
                parent = child;
            }
            parent.children.add(new Branch(task.knowledgePointId(), task.topic(), task.priority()));
        }
        List<OutlineNode> nodes = root.children.stream().map(Branch::freeze).toList();
        Map<Long, Task> byId = tasks.stream().collect(Collectors.toMap(Task::knowledgePointId, Function.identity()));
        List<Task> ordered = new ArrayList<>();
        // The conversation follows the same depth-first order the learner sees in the outline.
        appendLeaves(nodes, byId, ordered);
        return new Result(outline, emphasis, List.copyOf(ordered), nodes);
    }

    private static void appendLeaves(List<OutlineNode> nodes, Map<Long, Task> byId, List<Task> target) {
        for (OutlineNode node : nodes) {
            if (node.children().isEmpty()) target.add(byId.get(node.id()));
            else appendLeaves(node.children(), byId, target);
        }
    }

    private static final class Branch {
        final Long id;
        final String title;
        final String priority;
        final Map<String, Branch> groups = new LinkedHashMap<>();
        final List<Branch> children = new ArrayList<>();

        Branch(Long id, String title, String priority) {
            this.id = id;
            this.title = title;
            this.priority = priority;
        }

        OutlineNode freeze() {
            return new OutlineNode(id, title, priority, children.stream().map(Branch::freeze).toList());
        }
    }
}
