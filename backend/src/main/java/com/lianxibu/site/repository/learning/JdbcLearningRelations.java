package com.lianxibu.site.repository.learning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Original published relationships, read within the caller's existing snapshot. */
public final class JdbcLearningRelations implements LearningRelations {
    private final JdbcTemplate jdbc;
    public JdbcLearningRelations(DataSource source) {
        if (source==null) throw new IllegalArgumentException("learning data source required");
        jdbc=new JdbcTemplate(source);
    }
    JdbcLearningRelations(JdbcTemplate jdbc) {
        if (jdbc==null) throw new IllegalArgumentException("learning JDBC required");
        this.jdbc=jdbc;
    }
    private static final class NodeBuilder {
        final List<String> modules=new ArrayList<>(), dependencies=new ArrayList<>();
        final List<ResourceData> resources=new ArrayList<>();
        NodeFrame frame(){return new NodeFrame(modules,dependencies,resources);}
    }
    private static final class RouteBuilder {
        final List<String> modules=new ArrayList<>();
        long eligible;
        RouteFrame frame(){return new RouteFrame(modules,eligible);}
    }
    private static String anchors(Selection selection,String kind) {
        if (selection==null) throw new IllegalArgumentException("learning selection required");
        return " FROM learning_items i JOIN learning_packages p ON p.id=i.package_id AND p.state='published' ";
    }
    private static String where(Selection selection,String kind) {
        return " WHERE i.package_id=? AND i.kind='"+kind+"' AND i.id IN ("
                +String.join(",",Collections.nCopies(selection.itemIds().size(),"?"))+") ";
    }
    private static Object[] arguments(Selection selection) {
        var values=new ArrayList<Object>();values.add(selection.packageId());values.addAll(selection.itemIds());return values.toArray();
    }
    @Override public Map<String,NodeFrame> nodes(Selection selection) {
        String base=anchors(selection,"node"),filter=where(selection,"node");Object[] args=arguments(selection);
        var builders=new LinkedHashMap<String,NodeBuilder>();
        jdbc.query("SELECT i.id AS anchor_id,l.from_id AS module_id"+base
                +" LEFT JOIN learning_links l ON l.package_id=i.package_id AND l.to_kind='node' AND l.to_id=i.id "
                +"AND l.relation_kind='contains' AND l.from_kind='module'"+filter+" ORDER BY i.id,l.position_no,l.from_id",
                row->{var value=builders.computeIfAbsent(row.getString("anchor_id"),ignored->new NodeBuilder());
                    String module=row.getString("module_id");if(module!=null)value.modules.add(module);},args);
        if(builders.isEmpty())return Map.of();
        jdbc.query("SELECT i.id AS anchor_id,l.to_id"+base
                +" JOIN learning_links l ON l.package_id=i.package_id AND l.from_kind='node' AND l.from_id=i.id "
                +"AND l.relation_kind='requires' AND l.to_kind='node'"+filter+" ORDER BY i.id,l.position_no,l.to_id",
                row->{var value=builders.get(row.getString("anchor_id"));if(value==null)throw new IllegalStateException("learning anchor changed");
                    value.dependencies.add(row.getString("to_id"));},args);
        jdbc.query("SELECT i.id AS anchor_id,r.title,r.body_json"+base
                +" JOIN learning_links l ON l.package_id=i.package_id AND l.from_kind='node' AND l.from_id=i.id "
                +"AND l.relation_kind='references' AND l.to_kind='resource' "
                +"LEFT JOIN learning_items r ON r.package_id=l.package_id AND r.kind='resource' AND r.id=l.to_id"
                +filter+" ORDER BY i.id,l.position_no,l.to_id",
                row->{var value=builders.get(row.getString("anchor_id"));if(value==null)throw new IllegalStateException("learning anchor changed");
                    value.resources.add(new ResourceData(row.getString("title"),row.getString("body_json")));},args);
        var result=new LinkedHashMap<String,NodeFrame>();builders.forEach((id,builder)->result.put(id,builder.frame()));
        return Collections.unmodifiableMap(result);
    }
    @Override public Map<String,RouteFrame> routes(Selection selection) {
        String base=anchors(selection,"route"),filter=where(selection,"route");Object[] args=arguments(selection);
        var builders=new LinkedHashMap<String,RouteBuilder>();
        String routeModules=" LEFT JOIN learning_links rm ON rm.package_id=i.package_id AND rm.from_kind='route' "
                +"AND rm.from_id=i.id AND rm.relation_kind='contains' AND rm.to_kind='module' ";
        jdbc.query("SELECT i.id AS anchor_id,rm.to_id AS module_id"+base+routeModules+filter
                +" ORDER BY i.id,rm.position_no,rm.to_id",
                row->{var value=builders.computeIfAbsent(row.getString("anchor_id"),ignored->new RouteBuilder());
                    String module=row.getString("module_id");if(module!=null)value.modules.add(module);},args);
        if(builders.isEmpty())return Map.of();
        // Keep the former Java String-set semantics, including case and Unicode, one route at a time.
        String[] current={null};var eligible=new HashSet<String>();
        jdbc.query("SELECT i.id AS anchor_id,mn.to_id AS node_id"+base+routeModules
                +" LEFT JOIN learning_links mn ON mn.package_id=i.package_id AND mn.from_kind='module' "
                +"AND mn.from_id=rm.to_id AND mn.relation_kind='contains' AND mn.to_kind='node'"+filter+" ORDER BY i.id",
                row->{String route=row.getString("anchor_id");
                    if(!Objects.equals(current[0],route)){
                        if(current[0]!=null)builders.get(current[0]).eligible=eligible.size();
                        current[0]=route;eligible.clear();
                    }
                    if(!builders.containsKey(route))throw new IllegalStateException("learning anchor changed");
                    String node=row.getString("node_id");if(node!=null)eligible.add(node);},args);
        if(current[0]!=null)builders.get(current[0]).eligible=eligible.size();
        var result=new LinkedHashMap<String,RouteFrame>();builders.forEach((id,builder)->result.put(id,builder.frame()));
        return Collections.unmodifiableMap(result);
    }
}
