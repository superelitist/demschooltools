package controllers;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.Expr;
import com.avaje.ebean.Expression;
import com.avaje.ebean.RawSql;
import com.avaje.ebean.RawSqlBuilder;
import com.avaje.ebean.SqlUpdate;
import com.feth.play.module.pa.PlayAuthenticate;
import com.typesafe.plugin.*;

import models.*;

import org.markdown4j.Markdown4jProcessor;

import play.*;
import play.data.*;
import play.libs.Json;
import play.mvc.*;
import play.mvc.Http.Context;

@Security.Authenticated(Secured.class)
public class Application extends Controller {

    public static Date getDateFromString(String date_string) {
        if (!date_string.equals("")) {
            try
            {
                return new SimpleDateFormat("yyyy-MM-dd").parse(date_string);
            } catch (ParseException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public static List<Charge> getActiveSchoolMeetingReferrals() {
        return Charge.find.where().eq("referred_to_sm", true).eq("sm_decision", null).orderBy("id DESC").findList();
    }

    public static Result viewSchoolMeetingDecisions() {
        List<Charge> the_charges =
            Charge.find.where().
                eq("referred_to_sm", true).
                isNotNull("sm_decision").
                orderBy("id DESC").findList();
        return ok(views.html.view_sm_decisions.render(the_charges));
    }

	static List<Person> allPeople() {
        Tag cur_student_tag = Tag.find.where().eq("title", "Current Student").findUnique();
        Tag staff_tag = Tag.find.where().eq("title", "Staff").findUnique();

        List<Person> people = CRM.getPeopleForTag(cur_student_tag.id);
        people.addAll(CRM.getPeopleForTag(staff_tag.id));
		return people;
	}

    public static Result index() {
        List<Meeting> meetings = Meeting.find.orderBy("date DESC").findList();

        List<Charge> sm_charges = getActiveSchoolMeetingReferrals();

        List<Person> people = allPeople();
        Collections.sort(people, Person.SORT_DISPLAY_NAME);

        List<Entry> entries = Entry.find.findList();
        List<Entry> entries_with_charges = new ArrayList<Entry>();
        for (Entry e : entries) {
            if (e.charges.size() > 0) {
                entries_with_charges.add(e);
            }
        }

        Collections.sort(entries_with_charges, Entry.SORT_NUMBER);

        return ok(views.html.jc_index.render(meetings, sm_charges, people,
            entries_with_charges));
    }

    public static Result viewMeeting(int meeting_id) {
        return ok(views.html.view_meeting.render(Meeting.find.byId(meeting_id)));
    }

    public static Result viewMeetingResolutionPlans(int meeting_id) {
        return ok(views.html.view_meeting_resolution_plans.render(Meeting.find.byId(meeting_id)));
    }

	public static Result viewManual() {
		return ok(views.html.view_manual.render(Chapter.find.where("deleted = false").order("num ASC").findList()));
	}

    public static Result viewManualChanges() {
        Date now = new Date();
        Date seven_days_before = new Date(now.getTime() - 1000 * 60 * 60 * 24 * 7);
        List<ManualChange> changes = ManualChange.find.where().gt("date_entered", seven_days_before).findList();
        return ok(views.html.view_manual_changes.render(changes));
    }

	public static Result viewChapter(Integer id) {
		return ok(views.html.view_chapter.render(Chapter.find.byId(id)));
	}

    static List<Charge> getLastWeekCharges(Person p) {
        List<Charge> last_week_charges = new ArrayList<Charge>();

        Date now = new Date();

        for (Charge c : p.charges) {
            // Include if <= 7 days ago
            if (now.getTime() - c.the_case.meeting.date.getTime() <
                1000 * 60 * 60 * 24 * 7.5) {
                last_week_charges.add(c);
            }
        }

        return last_week_charges;
    }

    static Collection<String> getRecentResolutionPlans(Entry r) {
        Set<String> rps = new HashSet<String>();

        for (Charge c : r.charges) {
            if (!c.resolution_plan.toLowerCase().equals("warning") &&
                !c.resolution_plan.equals("")) {
                rps.add(c.resolution_plan);
            }
            if (rps.size() > 9) {
                break;
            }
        }

        return rps;
    }

    public static Result getPersonHistory(Integer id) {
        Person p = Person.find.byId(id);
        return ok(views.html.person_history.render(p, new PersonHistory(p, false), getLastWeekCharges(p)));
    }

    public static Result getRuleHistory(Integer id) {
        Entry r = Entry.find.byId(id);
        return ok(views.html.rule_history.render(r, new RuleHistory(r, false), getRecentResolutionPlans(r)));
    }

    public static Result viewPersonHistory(Integer id) {
        Person p = Person.find.byId(id);
        return ok(views.html.view_person_history.render(p, new PersonHistory(p), getLastWeekCharges(p)));
    }

    public static Result viewRuleHistory(Integer id) {
        Entry r = Entry.find.byId(id);
        return ok(views.html.view_rule_history.render(r, new RuleHistory(r), getRecentResolutionPlans(r)));
	}

    public static String jcPeople(String term) {
        List<Person> people = allPeople();
        Collections.sort(people, Person.SORT_DISPLAY_NAME);

		term = term.toLowerCase();

        List<Map<String, String> > result = new ArrayList<Map<String, String> > ();
        for (Person p : people) {
            if (p.searchStringMatches(term)) {
                HashMap<String, String> values = new HashMap<String, String>();
                values.put("label", p.getDisplayName());
                values.put("id", "" + p.person_id);
                result.add(values);
            }
        }

        return Json.stringify(Json.toJson(result));
    }

    public static String jsonRules(String term) {
		term = term.toLowerCase();

        List<Entry> rules = Entry.find.where().eq("deleted", false).orderBy("title ASC").findList();

        List<Map<String, String> > result = new ArrayList<Map<String, String> > ();
        for (Entry r : rules) {
            if (r.title.toLowerCase().contains(term)) {
                HashMap<String, String> values = new HashMap<String, String>();
                values.put("label", r.getNumber() + " " + r.title);
                values.put("id", "" + r.id);
                result.add(values);
            }
        }

        return Json.stringify(Json.toJson(result));
    }

    public static Result getLastRp(Integer personId, Integer ruleId) {
        Date now = new Date();

        for (Charge c : Person.find.byId(personId).charges) {
            if (c.rule != null &&
                c.rule.id == ruleId &&
                now.getTime() - c.the_case.meeting.date.getTime() > 1000 * 60 * 60 * 24) {
                return ok("Last RP for this charge (from case #" + c.the_case.case_number +
                    "): " + c.resolution_plan);
            }
        }
        return ok("No previous charge.");
    }

    public static String formatDayOfWeek(Date d) {
        return new SimpleDateFormat("EE").format(d);
    }

    public static String formatDateShort(Date d) {
        return new SimpleDateFormat("MM/dd").format(d);
    }

    public static String formatMeetingDate(Date d) {
        return new SimpleDateFormat("EE--MMMM dd, yyyy").format(d);
    }

	public static String yymmddDate(Date d ) {
		return new SimpleDateFormat("yyyy-M-d").format(d);
	}

    public static String currentUsername() {
        return Context.current().request().username();
    }

    public static boolean isUserEditor(String username) {
        return username != null && username.contains("@");
    }

    public static boolean isCurrentUserEditor() {
        return isUserEditor(currentUsername());
    }

    public static String getRemoteIp() {
        Context ctx = Context.current();
        Configuration conf = getConfiguration();

        if (conf.getBoolean("heroku_ips")) {
            String header = ctx.request().getHeader("X-Forwarded-For");
            if (header == null) {
                return "unknown-ip";
            }
            String splits[] = header.split("[, ]");
            return splits[splits.length - 1];
        } else {
            return ctx.request().remoteAddress();
        }
	}

    public static User getCurrentUser() {
        return User.findByAuthUserIdentity(
            PlayAuthenticate.getUser(Context.current().session()));
    }

    public static Configuration getConfiguration() {
		return Play.application().configuration().getConfig("school_crm");
	}

	public static String markdown(String input) {
        if (input == null) {
            return "";
        }
		try {
			return new Markdown4jProcessor().process(input);
		} catch (IOException e) {
			return e.toString() + "<br><br>" + input;
		}
	}
}
