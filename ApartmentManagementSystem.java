import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Apartment Management System (Java Swing + SQLite)
 *
 * Roles:    LANDLORD and TENANT, each with a different dashboard.
 * Database: SQLite file "apartment.db" (created automatically), accessed through JDBC.
 * Needs:    the sqlite-jdbc .jar added to the classpath.
 *
 * Demo accounts:
 *   Landlord: admin   / admin123
 *   Tenant:   tenant1 / 1234
 */
public class ApartmentManagementSystem extends JFrame {

    // =====================================================================
    //  DATABASE LAYER  (all SQL lives here)
    // =====================================================================
    static class Database {
        static final String URL = "jdbc:sqlite:apartment.db";

        static Connection connect() throws SQLException {
            return DriverManager.getConnection(URL);
        }

        // Passwords are stored as SHA-256 hashes, never as plain text.
        static String hash(String text) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        }

        // INSERT / UPDATE / DELETE
        static void exec(String sql, Object... params) throws SQLException {
            try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                ps.executeUpdate();
            }
        }

        // SELECT -> list of rows
        static List<Object[]> rows(String sql, Object... params) throws SQLException {
            List<Object[]> out = new ArrayList<>();
            try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        Object[] row = new Object[n];
                        for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
                        out.add(row);
                    }
                }
            }
            return out;
        }

        // SELECT -> first column of first row (or null)
        static Object scalar(String sql, Object... params) throws SQLException {
            List<Object[]> r = rows(sql, params);
            return r.isEmpty() ? null : r.get(0)[0];
        }

        static void init() throws SQLException {
            exec("CREATE TABLE IF NOT EXISTS users ("
               + "username TEXT PRIMARY KEY, password TEXT NOT NULL, role TEXT NOT NULL)");
            exec("CREATE TABLE IF NOT EXISTS units ("
               + "unit_no TEXT PRIMARY KEY, rent REAL NOT NULL, occupied INTEGER NOT NULL DEFAULT 0)");
            exec("CREATE TABLE IF NOT EXISTS tenants ("
               + "tenant_id TEXT PRIMARY KEY, name TEXT NOT NULL, "
               + "unit_no TEXT REFERENCES units(unit_no), "
               + "username TEXT REFERENCES users(username), balance REAL NOT NULL DEFAULT 0)");
            exec("CREATE TABLE IF NOT EXISTS payments ("
               + "id INTEGER PRIMARY KEY AUTOINCREMENT, tenant_id TEXT REFERENCES tenants(tenant_id), "
               + "date TEXT NOT NULL, amount REAL NOT NULL)");
            exec("CREATE TABLE IF NOT EXISTS requests ("
               + "id INTEGER PRIMARY KEY AUTOINCREMENT, tenant_id TEXT REFERENCES tenants(tenant_id), "
               + "description TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING')");
            exec("CREATE TABLE IF NOT EXISTS announcements ("
               + "id INTEGER PRIMARY KEY AUTOINCREMENT, message TEXT NOT NULL, date_posted TEXT NOT NULL)");

            // Sample data, only on the very first run
            Number count = (Number) scalar("SELECT COUNT(*) FROM users");
            if (count.intValue() == 0) {
                exec("INSERT INTO users VALUES(?,?,?)", "admin", hash("admin123"), "LANDLORD");
                exec("INSERT INTO users VALUES(?,?,?)", "tenant1", hash("1234"), "TENANT");
                exec("INSERT INTO units VALUES(?,?,?)", "101", 5000.0, 1);
                exec("INSERT INTO units VALUES(?,?,?)", "102", 5500.0, 0);
                exec("INSERT INTO tenants VALUES(?,?,?,?,?)", "T001", "Juan Dela Cruz", "101", "tenant1", 0.0);
            }
        }
    }

    // =====================================================================
    //  SMALL UI HELPERS
    // =====================================================================
    interface Action { void run() throws Exception; }

    // Runs an action and shows a friendly message if something goes wrong
    static void safe(Component parent, Action action) {
        try {
            action.run();
        } catch (NumberFormatException e) {
            msg(parent, "Please enter a valid number.");
        } catch (java.time.format.DateTimeParseException e) {
            msg(parent, "Date must be in YYYY-MM-DD format.");
        } catch (Exception e) {
            msg(parent, e.getMessage());
        }
    }

    static void msg(Component parent, String text) {
        JOptionPane.showMessageDialog(parent, text);
    }

    // A table that loads its rows from a SQL query
    static class DataTable extends JScrollPane {
        final DefaultTableModel model;
        final JTable table;
        final String sql;
        final Object[] params;

        DataTable(String[] columns, String sql, Object... params) {
            this.model = new DefaultTableModel(columns, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            this.table = new JTable(model);
            this.sql = sql;
            this.params = params;
            setViewportView(table);
            refresh();
        }

        void refresh() {
            try {
                model.setRowCount(0);
                for (Object[] row : Database.rows(sql, params)) model.addRow(row);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        Object selected(int column) {
            int r = table.getSelectedRow();
            return r < 0 ? null : model.getValueAt(r, column);
        }
    }

    // A row of labels / fields / buttons (Strings become labels)
    static JPanel row(Object... items) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Object o : items) p.add(o instanceof String ? new JLabel((String) o) : (Component) o);
        return p;
    }

    // A tab layout: form rows on top, table in the center
    static JPanel tab(JComponent center, JPanel... tops) {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JPanel t : tops) north.add(t);
        p.add(north, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);
        return p;
    }

    // =====================================================================
    //  MAIN WINDOW
    // =====================================================================
    public ApartmentManagementSystem() {
        super("Apartment Management System");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(920, 560);
        setLocationRelativeTo(null);
        showLogin();
    }

    void showPanel(JPanel panel) {
        setContentPane(panel);
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------------
    //  LOGIN
    // ---------------------------------------------------------------------
    void showLogin() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 6, 6, 6);
        g.gridx = 0; g.gridwidth = 2; g.gridy = 0;

        JLabel title = new JLabel("Apartment Management System");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        p.add(title, g);

        JTextField user = new JTextField(16);
        JPasswordField pass = new JPasswordField(16);
        g.gridwidth = 1;
        g.gridy = 1; g.gridx = 0; p.add(new JLabel("Username:"), g);
        g.gridx = 1; p.add(user, g);
        g.gridy = 2; g.gridx = 0; p.add(new JLabel("Password:"), g);
        g.gridx = 1; p.add(pass, g);

        JButton login = new JButton("Login");
        g.gridy = 3; g.gridx = 0; g.gridwidth = 2; p.add(login, g);

        JLabel hint = new JLabel("Demo: admin / admin123  (landlord)   tenant1 / 1234  (tenant)");
        g.gridy = 4; p.add(hint, g);

        Action doLogin = () -> {
            String u = user.getText().trim();
            String pw = new String(pass.getPassword());
            List<Object[]> r = Database.rows(
                "SELECT role FROM users WHERE username=? AND password=?", u, Database.hash(pw));
            if (r.isEmpty()) {
                msg(this, "Invalid username or password.");
                return;
            }
            String role = (String) r.get(0)[0];
            if (role.equals("LANDLORD")) {
                showPanel(landlordDashboard());
            } else {
                Object id = Database.scalar("SELECT tenant_id FROM tenants WHERE username=?", u);
                if (id == null) {
                    msg(this, "No tenant record is linked to this account.");
                    return;
                }
                showPanel(tenantDashboard((String) id));
            }
        };
        login.addActionListener(e -> safe(this, doLogin));
        pass.addActionListener(e -> safe(this, doLogin)); // Enter key
        showPanel(p);
    }

    JPanel dashboard(String title, JTabbedPane tabs, List<DataTable> tables) {
        tabs.addChangeListener(e -> { for (DataTable t : tables) t.refresh(); });

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 16f));
        JButton out = new JButton("Logout");
        out.addActionListener(e -> showLogin());
        bar.add(l, BorderLayout.WEST);
        bar.add(out, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout());
        p.add(bar, BorderLayout.NORTH);
        p.add(tabs, BorderLayout.CENTER);
        return p;
    }

    // ---------------------------------------------------------------------
    //  LANDLORD DASHBOARD
    // ---------------------------------------------------------------------
    JPanel landlordDashboard() {
        JTabbedPane tabs = new JTabbedPane();
        List<DataTable> tables = new ArrayList<>();

        // ---- Units ----
        DataTable unitsTable = new DataTable(new String[]{"Unit", "Monthly rent", "Status"},
            "SELECT unit_no, rent, CASE occupied WHEN 1 THEN 'Occupied' ELSE 'Vacant' END "
          + "FROM units ORDER BY unit_no");
        tables.add(unitsTable);
        JTextField uNo = new JTextField(6), uRent = new JTextField(8);
        JButton addUnit = new JButton("Add unit");
        addUnit.addActionListener(e -> safe(this, () -> {
            String no = uNo.getText().trim();
            double rent = Double.parseDouble(uRent.getText().trim());
            if (no.isEmpty() || rent <= 0) throw new Exception("Enter a unit number and a rent above 0.");
            if (Database.scalar("SELECT 1 FROM units WHERE unit_no=?", no) != null)
                throw new Exception("That unit already exists.");
            Database.exec("INSERT INTO units VALUES(?,?,0)", no, rent);
            uNo.setText(""); uRent.setText("");
            unitsTable.refresh();
        }));
        tabs.addTab("Units", tab(unitsTable, row("Unit no:", uNo, "Monthly rent:", uRent, addUnit)));

        // ---- Tenants ----
        DataTable tenantsTable = new DataTable(
            new String[]{"Tenant ID", "Name", "Unit", "Username", "Balance"},
            "SELECT tenant_id, name, unit_no, username, balance FROM tenants ORDER BY tenant_id");
        tables.add(tenantsTable);
        JTextField tId = new JTextField(6), tName = new JTextField(14), tUnit = new JTextField(5);
        JTextField tUser = new JTextField(10);
        JPasswordField tPass = new JPasswordField(10);
        JButton addTenant = new JButton("Add tenant");
        addTenant.addActionListener(e -> safe(this, () -> {
            String id = tId.getText().trim(), name = tName.getText().trim();
            String unit = tUnit.getText().trim(), user = tUser.getText().trim();
            String pw = new String(tPass.getPassword());
            if (id.isEmpty() || name.isEmpty() || unit.isEmpty() || user.isEmpty() || pw.isEmpty())
                throw new Exception("Please fill in all fields.");
            if (Database.scalar("SELECT 1 FROM tenants WHERE tenant_id=?", id) != null)
                throw new Exception("Tenant ID already exists.");
            if (Database.scalar("SELECT 1 FROM users WHERE username=?", user) != null)
                throw new Exception("Username is already taken.");
            Object occupied = Database.scalar("SELECT occupied FROM units WHERE unit_no=?", unit);
            if (occupied == null) throw new Exception("Unit not found.");
            if (((Number) occupied).intValue() == 1) throw new Exception("That unit is already occupied.");

            Database.exec("INSERT INTO users VALUES(?,?,?)", user, Database.hash(pw), "TENANT");
            Database.exec("INSERT INTO tenants VALUES(?,?,?,?,0)", id, name, unit, user);
            Database.exec("UPDATE units SET occupied=1 WHERE unit_no=?", unit);
            tId.setText(""); tName.setText(""); tUnit.setText(""); tUser.setText(""); tPass.setText("");
            tenantsTable.refresh();
        }));
        JButton billRent = new JButton("Bill monthly rent to selected tenant");
        billRent.addActionListener(e -> safe(this, () -> {
            Object id = tenantsTable.selected(0);
            if (id == null) throw new Exception("Select a tenant in the table first.");
            Object rent = Database.scalar(
                "SELECT rent FROM units WHERE unit_no=(SELECT unit_no FROM tenants WHERE tenant_id=?)", id);
            Database.exec("UPDATE tenants SET balance = balance + ? WHERE tenant_id=?", rent, id);
            tenantsTable.refresh();
        }));
        tabs.addTab("Tenants", tab(tenantsTable,
            row("ID:", tId, "Name:", tName, "Unit:", tUnit),
            row("Username:", tUser, "Password:", tPass, addTenant),
            row(billRent)));

        // ---- Payments ----
        DataTable paymentsTable = new DataTable(
            new String[]{"No.", "Tenant ID", "Name", "Date", "Amount"},
            "SELECT p.id, p.tenant_id, t.name, p.date, p.amount FROM payments p "
          + "JOIN tenants t ON t.tenant_id = p.tenant_id ORDER BY p.id DESC");
        tables.add(paymentsTable);
        JTextField pId = new JTextField(6);
        JTextField pDate = new JTextField(LocalDate.now().toString(), 9);
        JTextField pAmount = new JTextField(8);
        JButton addPayment = new JButton("Record payment");
        addPayment.addActionListener(e -> safe(this, () -> {
            String id = pId.getText().trim();
            String date = LocalDate.parse(pDate.getText().trim()).toString();
            double amount = Double.parseDouble(pAmount.getText().trim());
            if (Database.scalar("SELECT 1 FROM tenants WHERE tenant_id=?", id) == null)
                throw new Exception("Tenant not found.");
            if (amount <= 0) throw new Exception("Amount must be above 0.");
            Database.exec("INSERT INTO payments(tenant_id, date, amount) VALUES(?,?,?)", id, date, amount);
            Database.exec("UPDATE tenants SET balance = balance - ? WHERE tenant_id=?", amount, id);
            pAmount.setText("");
            paymentsTable.refresh();
        }));
        tabs.addTab("Payments", tab(paymentsTable,
            row("Tenant ID:", pId, "Date (YYYY-MM-DD):", pDate, "Amount:", pAmount, addPayment)));

        // ---- Requests ----
        DataTable requestsTable = new DataTable(
            new String[]{"No.", "Tenant ID", "Name", "Request", "Status"},
            "SELECT r.id, r.tenant_id, t.name, r.description, r.status FROM requests r "
          + "JOIN tenants t ON t.tenant_id = r.tenant_id "
          + "ORDER BY CASE r.status WHEN 'PENDING' THEN 0 ELSE 1 END, r.id");
        tables.add(requestsTable);
        JButton doneSelected = new JButton("Mark selected as done");
        doneSelected.addActionListener(e -> safe(this, () -> {
            Object id = requestsTable.selected(0);
            if (id == null) throw new Exception("Select a request first.");
            Database.exec("UPDATE requests SET status='DONE' WHERE id=?", id);
            requestsTable.refresh();
        }));
        JButton doneNext = new JButton("Mark oldest pending as done (queue)");
        doneNext.addActionListener(e -> safe(this, () -> {
            Object id = Database.scalar("SELECT MIN(id) FROM requests WHERE status='PENDING'");
            if (id == null) throw new Exception("No pending requests.");
            Database.exec("UPDATE requests SET status='DONE' WHERE id=?", id);
            requestsTable.refresh();
        }));
        tabs.addTab("Requests", tab(requestsTable, row(doneSelected, doneNext)));

        // ---- Announcements ----
        DataTable annTable = new DataTable(new String[]{"Date", "Announcement"},
            "SELECT date_posted, message FROM announcements ORDER BY id DESC");
        tables.add(annTable);
        JTextField aText = new JTextField(40);
        JButton post = new JButton("Post");
        post.addActionListener(e -> safe(this, () -> {
            String text = aText.getText().trim();
            if (text.isEmpty()) throw new Exception("Write an announcement first.");
            Database.exec("INSERT INTO announcements(message, date_posted) VALUES(?,?)",
                          text, LocalDate.now().toString());
            aText.setText("");
            annTable.refresh();
        }));
        tabs.addTab("Announcements", tab(annTable, row("Message:", aText, post)));

        return dashboard("Landlord Dashboard", tabs, tables);
    }

    // ---------------------------------------------------------------------
    //  TENANT DASHBOARD
    // ---------------------------------------------------------------------
    JPanel tenantDashboard(String tenantId) {
        JTabbedPane tabs = new JTabbedPane();
        List<DataTable> tables = new ArrayList<>();

        // ---- My account: info on top, payment history below ----
        DataTable info = new DataTable(new String[]{"Name", "Tenant ID", "Unit", "Monthly rent", "Balance"},
            "SELECT t.name, t.tenant_id, t.unit_no, u.rent, t.balance FROM tenants t "
          + "JOIN units u ON u.unit_no = t.unit_no WHERE t.tenant_id=?", tenantId);
        info.setPreferredSize(new Dimension(100, 70));
        DataTable payments = new DataTable(new String[]{"Date", "Amount paid"},
            "SELECT date, amount FROM payments WHERE tenant_id=? ORDER BY id DESC", tenantId);
        tables.add(info);
        tables.add(payments);
        JPanel account = new JPanel(new BorderLayout(5, 5));
        account.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        account.add(info, BorderLayout.NORTH);
        account.add(payments, BorderLayout.CENTER);
        tabs.addTab("My Account", account);

        // ---- Requests ----
        DataTable requests = new DataTable(new String[]{"No.", "Request", "Status"},
            "SELECT id, description, status FROM requests WHERE tenant_id=? ORDER BY id DESC", tenantId);
        tables.add(requests);
        JTextField desc = new JTextField(40);
        JButton submit = new JButton("Submit request");
        submit.addActionListener(e -> safe(this, () -> {
            String text = desc.getText().trim();
            if (text.isEmpty()) throw new Exception("Describe your request first.");
            Database.exec("INSERT INTO requests(tenant_id, description) VALUES(?,?)", tenantId, text);
            desc.setText("");
            requests.refresh();
        }));
        tabs.addTab("My Requests", tab(requests, row("Request:", desc, submit)));

        // ---- Announcements ----
        DataTable ann = new DataTable(new String[]{"Date", "Announcement"},
            "SELECT date_posted, message FROM announcements ORDER BY id DESC");
        tables.add(ann);
        tabs.addTab("Announcements", tab(ann));

        String name = "";
        try {
            name = (String) Database.scalar("SELECT name FROM tenants WHERE tenant_id=?", tenantId);
        } catch (Exception ignored) { }
        return dashboard("Tenant Dashboard - " + name, tabs, tables);
    }

    // =====================================================================
    //  START
    // =====================================================================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                Database.init();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null,
                    "Could not open the database.\n"
                  + "Make sure the sqlite-jdbc .jar is added to the classpath.\n\n" + e.getMessage());
                return;
            }
            new ApartmentManagementSystem().setVisible(true);
        });
    }
}
