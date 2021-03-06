package controllers;

import java.util.*;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlQuery;
import com.avaje.ebean.SqlRow;
import com.avaje.ebean.SqlUpdate;

import org.mindrot.jbcrypt.BCrypt;

import models.*;

import service.MyUserService;

import play.data.*;
import play.mvc.*;

@With(DumpOnError.class)
@Secured.Auth(UserRole.ROLE_ALL_ACCESS)
public class Settings extends Controller {

    public Result viewSettings() {
        List<NotificationRule> rules = NotificationRule.find.where()
            .eq("organization", OrgConfig.get().org)
            .order("the_type DESC, tag.id")
            .findList();

        return ok(views.html.view_settings.render(rules, OrgConfig.get().org, Application.getConfiguration()));
    }

    public Result editSettings() {
        final Map<String, String[]> values = request().body().asFormUrlEncoded();
        OrgConfig.get().org.updateFromForm(values);
        CachedPage.clearAll();

        String new_password = null;
        if (values.containsKey("custodia_student_password")) {
            new_password = values.get("custodia_student_password")[0];
        }
        if (new_password != null && !new_password.trim().isEmpty()) {
            Utils.setCustodiaPassword(new_password);
        }

        return redirect(routes.Settings.viewSettings());
    }

    public Result editNotifications() {
        final Map<String, String[]> values = request().body().asFormUrlEncoded();

        if (values.containsKey("remove_notification_id")) {
            NotificationRule.findById(
                Integer.parseInt(values.get("remove_notification_id")[0])).delete();
        } else {
            String email = values.get("email")[0];
            if (!email.matches("^\\S+@\\S+.\\S+$")) {
                flash("error", "'" + email + "' does not seem to be a valid email address. Notification not created.");
                return redirect(routes.Settings.viewSettings());
            }

            if (values.containsKey("tag_id")) {
                Tag t = Tag.findById(Integer.parseInt(values.get("tag_id")[0]));
                NotificationRule.create(NotificationRule.TYPE_TAG, t, email);
            }

            if (values.containsKey("comment")) {
                NotificationRule.create(NotificationRule.TYPE_COMMENT, null, email);
            }
            if (values.containsKey("school_meeting")) {
                NotificationRule.create(NotificationRule.TYPE_SCHOOL_MEETING, null, email);
            }
        }

        return redirect(routes.Settings.viewSettings());
    }

    public Result viewTaskLists() {
        Form<TaskList> list_form = Form.form(TaskList.class);
        return ok(views.html.view_task_lists.render(TaskList.allForOrg(), list_form));
    }

    public Result viewPassword() {
        return ok(views.html.view_password.render(flash("notice")));
    }

    public Result editPassword() {
        final Map<String, String[]> values = request().body().asFormUrlEncoded();

        String password = values.get("password")[0];
        String confirmPassword = values.get("confirmPassword")[0];

        if (!password.equals(confirmPassword)) {
            flash("notice", "The two passwords you entered did not match");
        } else if (password.length() < 8) {
            flash("notice", "Please choose a password that is at least 8 characters");
        } else {
            User u = Application.getCurrentUser();
            u.hashed_password = BCrypt.hashpw(password, BCrypt.gensalt());
            u.save();
            play.libs.mailer.Email mail = new play.libs.mailer.Email();
            mail.setSubject("DemSchoolTools password changed");
            mail.addTo(u.email);
            mail.setFrom("DemSchoolTools <noreply@demschooltools.com>");
            mail.setBodyText("Hi " + u.name + ",\n\nYour DemSchoolTools password was changed today (" +
                Application.formatDateTimeLong() +
                "). \n\nIf it was not you who changed it, please investigate what is going on! " +
                "Feel free to contact Evan (schmave@gmail.com) for help.");
            play.libs.mailer.MailerPlugin.send(mail);
            flash("notice", "Your password was changed");
        }

        return redirect(routes.Settings.viewPassword());
    }

    public Result viewTaskList(Integer id) {
        TaskList list = TaskList.findById(id);
        Form<TaskList> list_form = Form.form(TaskList.class);
        Form<Task> task_form = Form.form(Task.class);
        return ok(views.html.settings_task_list.render(
            list, list_form.fill(list), task_form));
    }

    public Result newTask() {
        Form<Task> task_form = Form.form(Task.class);
        Task t = task_form.bindFromRequest().get();
        t.enabled = true;
        t.save();

        return redirect(routes.Settings.viewTaskList(t.task_list.id));
    }

    public Result newTaskList() {
        Form<TaskList> list_form = Form.form(TaskList.class);
        TaskList list = list_form.bindFromRequest().get();
        list.organization = OrgConfig.get().org;

        list.title = list.title.trim();
        if (list.title.equals("")) {
            list.title = "Untitled checklist";
        }

        Map<String, String[]> form_data = request().body().asFormUrlEncoded();
        if (form_data.get("tag_id") == null) {
            flash("error", "No tag specified for checklist. No checklist was created.");
            return redirect(routes.Settings.viewTaskLists());
        }
        list.tag = Tag.findById(Integer.parseInt(form_data.get("tag_id")[0]));

        list.save();
        list.refresh();

        return redirect(routes.Settings.viewTaskList(list.id));
    }

    public Result editTask(Integer id) {
        Form<Task> task_form = Form.form(Task.class);
        Form<Task> filled_form = task_form.fill(Task.findById(id));

        return ok(views.html.edit_task.render(filled_form,
            TaskList.allForOrg()));
    }

    public Result saveTask() {
        Form<Task> task_form = Form.form(Task.class);
        Form<Task> filled_form = task_form.bindFromRequest();
        Task t = filled_form.get();
        if (filled_form.apply("enabled").value().equals("false")) {
            t.enabled = false;
        }
        t.update();

        return redirect(routes.Settings.viewTaskList(t.task_list.id));
    }

    public Result saveTaskList() {
        Form<TaskList> list_form = Form.form(TaskList.class);
        Form<TaskList> filled_form = list_form.bindFromRequest();
        TaskList list = filled_form.get();

        Map<String, String[]> form_data = request().body().asFormUrlEncoded();
        if (form_data.containsKey("tag_id")) {
            list.tag = Tag.findById(Integer.parseInt(form_data.get("tag_id")[0]));
        }

        list.update();

        return redirect(routes.Settings.viewTaskList(list.id));
    }

    public Result viewAccess() {
        Organization org = OrgConfig.get().org;
        List<User> users =
            User.find.where().eq("organization", org)
            .ne("name", MyUserService.DUMMY_USERNAME)
            .order("name ASC")
            .findList();

        String allowed_ip = "";
        String sql = "select ip from allowed_ips where organization_id=:org_id";
        SqlQuery sqlQuery = Ebean.createSqlQuery(sql);
        sqlQuery.setParameter("org_id", org.id);
        List<SqlRow> result = sqlQuery.findList();
        if (result.size() > 0) {
            allowed_ip = result.get(0).getString("ip");
        }

        Form<User> user_form = Form.form(User.class);
        return ok(views.html.view_access.render(users, allowed_ip, user_form));
    }

    public Result saveAccess() {
        Map<String, String[]> form_data = request().body().asFormUrlEncoded();
        Organization org = OrgConfig.get().org;

        if (form_data.containsKey("allowed_ip")) {
            String sql = "DELETE from allowed_ips where organization_id=:org_id";
            SqlUpdate update = Ebean.createSqlUpdate(sql);
            update.setParameter("org_id", org.id);
            update.execute();

            sql = "INSERT into allowed_ips (ip, organization_id) VALUES(:ip, :org_id)";
            update = Ebean.createSqlUpdate(sql);
            update.setParameter("org_id", org.id);
            update.setParameter("ip", form_data.get("allowed_ip")[0]);
            update.execute();
        }

        return redirect(routes.Settings.viewAccess());
    }

    public Result editUser(Integer id) {
        User u = User.findById(id);

        Form<User> user_form = Form.form(User.class);
        return ok(views.html.edit_user.render(u, user_form.fill(u)));
    }

    public Result saveUser() {
        Form<User> user_form = Form.form(User.class);
        Form<User> filled_form = user_form.bindFromRequest();
        User u = filled_form.get();
        Map<String, String[]> form_data = request().body().asFormUrlEncoded();

        User orig_user = User.findById(u.id);

        for (UserRole r : orig_user.roles) {
            r.delete();
        }

        for (String role : UserRole.ALL_ROLES) {
            if (form_data.containsKey(role) && form_data.get(role)[0].equals("true")) {
                UserRole.create(u, role);
            }
        }

        if (filled_form.apply("active").value().equals("false")) {
            u.active = false;
            Ebean.deleteAll(orig_user.linkedAccounts);
        }

        if (!orig_user.email.equals(u.email)) {
            Ebean.deleteAll(orig_user.linkedAccounts);
        }

        u.update();

        return redirect(routes.Settings.viewAccess());
    }

    public Result newUser() {
        Map<String, String[]> form_data = request().body().asFormUrlEncoded();

        Organization org = OrgConfig.get().org;
        final String email = form_data.get("email")[0].trim();
        final String name = form_data.get("name")[0].trim();

        User existing_user = User.findByEmail(email);
        User new_user = null;

        if (existing_user != null) {
            if (existing_user.organization.id.equals(org.id)) {
                if (existing_user.name.equals(MyUserService.DUMMY_USERNAME)) {
                    new_user = existing_user;
                    new_user.name = name;
                    new_user.save();
                } else {
                    flash("error", "That email address (" + email + ") already has an account.");
                    return redirect(routes.Settings.viewAccess());
                }
            } else {
                flash("error", "That email address (" + email + ") already has an account for another school. " +
                    "Please contact Evan at schmave@gmail.com for help.");
                return redirect(routes.Settings.viewAccess());
            }
        }

        if (new_user == null) {
            new_user = User.create(email, name, org);
        } else {
            for (UserRole r : new_user.roles) {
                r.delete();
            }
        }

        UserRole.create(new_user, UserRole.ROLE_VIEW_JC);

        return redirect(routes.Settings.viewAccess());
    }
}
