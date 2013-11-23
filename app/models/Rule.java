package models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.persistence.*;

import play.db.ebean.Model;
import play.db.ebean.Model.Finder;

@Entity
public class Rule extends Model implements Comparable<Rule> {
    @Id
    public Integer id;

    public String title;

    public static Finder<Integer, Rule> find = new Finder(
        Integer.class, Rule.class
    );

    public int compareTo(Rule other) {
        return title.compareTo(other.title);
    }
}
