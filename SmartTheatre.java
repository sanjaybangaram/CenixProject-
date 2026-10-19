import javax.swing.*;
import java.awt.*;
import java.sql.*;
import java.util.UUID;

public class SmartTheatre extends JFrame {

    // CHANGE THESE SETTINGS TO MATCH YOUR MYSQL ACCOUNT
    private static final String URL =
            "jdbc:mysql://localhost:3306/smart_theatre";
    private static final String USER = "root";
    private static final String PASSWORD = "Vamsi@2008";

    static class Movie {
        int id;
        String title, genre;
        double price;

        Movie(int id, String title, String genre, double price) {
            this.id = id;
            this.title = title;
            this.genre = genre;
            this.price = price;
        }

        public String toString() {
            return title + " | " + genre + " | Rs. " + price;
        }
    }

    private final JPanel panel = new JPanel(new BorderLayout(10, 10));
    private final DefaultListModel<Movie> movieModel =
            new DefaultListModel<>();
    private final JList<Movie> movieList = new JList<>(movieModel);

    public SmartTheatre() {
        setTitle("Smart Theatre Management System");
        setSize(850, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setContentPane(panel);

        initializeDatabase();
        showHome();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    // Database tables and initial sample data
    private void initializeDatabase() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");

            try (Connection con = connect();
                 Statement st = con.createStatement()) {

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS movies (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        title VARCHAR(100) NOT NULL,
                        genre VARCHAR(50),
                        price DECIMAL(10,2) NOT NULL
                    )
                    """);

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS shows (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        movie_id INT NOT NULL,
                        show_time DATETIME NOT NULL,
                        FOREIGN KEY (movie_id) REFERENCES movies(id)
                    )
                    """);

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bookings (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        ticket_id VARCHAR(60) UNIQUE NOT NULL,
                        customer VARCHAR(100) NOT NULL,
                        movie_id INT NOT NULL,
                        show_id INT NOT NULL,
                        seat VARCHAR(10) NOT NULL,
                        amount DECIMAL(10,2) NOT NULL,
                        food VARCHAR(100),
                        booking_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (show_id, seat),
                        FOREIGN KEY (movie_id) REFERENCES movies(id),
                        FOREIGN KEY (show_id) REFERENCES shows(id)
                    )
                    """);

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS coupons (
                        code VARCHAR(30) PRIMARY KEY,
                        discount INT NOT NULL
                    )
                    """);

                st.executeUpdate("""
                    INSERT INTO movies(title, genre, price)
                    SELECT 'Avengers', 'Action', 180.00
                    WHERE NOT EXISTS (
                        SELECT 1 FROM movies WHERE title = 'Avengers'
                    )
                    """);

                st.executeUpdate("""
                    INSERT INTO movies(title, genre, price)
                    SELECT 'Inception', 'Sci-Fi', 160.00
                    WHERE NOT EXISTS (
                        SELECT 1 FROM movies WHERE title = 'Inception'
                    )
                    """);

                st.executeUpdate("""
                    INSERT INTO movies(title, genre, price)
                    SELECT 'Interstellar', 'Sci-Fi', 200.00
                    WHERE NOT EXISTS (
                        SELECT 1 FROM movies WHERE title = 'Interstellar'
                    )
                    """);

                st.executeUpdate("""
                    INSERT INTO coupons(code, discount)
                    VALUES ('WELCOME10', 10)
                    ON DUPLICATE KEY UPDATE discount = 10
                    """);

            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "Database initialization failed:\n"
                            + e.getMessage()
                            + "\n\nCheck MySQL and your connection settings.");
        }
    }

    // Main movie selection screen
    private void showHome() {
        panel.removeAll();
        panel.setLayout(new BorderLayout(10, 10));
        movieModel.clear();

        JLabel heading = new JLabel(
                "SMART THEATRE TICKET BOOKING",
                SwingConstants.CENTER);
        heading.setFont(new Font("Arial", Font.BOLD, 24));
        panel.add(heading, BorderLayout.NORTH);

        try (Connection con = connect();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, title, genre, price FROM movies ORDER BY id")) {

            while (rs.next()) {
                movieModel.addElement(new Movie(
                        rs.getInt("id"),
                        rs.getString("title"),
                        rs.getString("genre"),
                        rs.getDouble("price")));
            }

        } catch (SQLException e) {
            message("Could not load movies: " + e.getMessage());
        }

        movieList.setFont(new Font("Arial", Font.PLAIN, 18));
        panel.add(new JScrollPane(movieList), BorderLayout.CENTER);

        JButton book = new JButton("Book Ticket");
        JButton admin = new JButton("Admin Panel");

        JPanel buttons = new JPanel();
        buttons.add(book);
        buttons.add(admin);
        panel.add(buttons, BorderLayout.SOUTH);

        book.addActionListener(e -> {
            Movie movie = movieList.getSelectedValue();
            if (movie == null) {
                message("Please select a movie.");
                return;
            }
            showSeats(movie);
        });

        admin.addActionListener(e -> adminLogin());

        refresh();
    }

    // Find or create today's show
    private int getShowId(int movieId) throws SQLException {
        try (Connection con = connect()) {
            java.time.LocalDateTime now =
                    java.time.LocalDateTime.now();
            java.time.LocalDateTime start =
                    now.toLocalDate().atStartOfDay();
            java.time.LocalDateTime end = start.plusDays(1);

            String query = """
                SELECT id FROM shows
                WHERE movie_id = ? AND show_time >= ? AND show_time < ?
                ORDER BY show_time LIMIT 1
                """;

            try (PreparedStatement ps = con.prepareStatement(query)) {
                ps.setInt(1, movieId);
                ps.setTimestamp(2, Timestamp.valueOf(start));
                ps.setTimestamp(3, Timestamp.valueOf(end));

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getInt("id");
                }
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO shows(movie_id, show_time) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {

                ps.setInt(1, movieId);
                ps.setTimestamp(2, Timestamp.valueOf(now.plusHours(2)
                        .withSecond(0).withNano(0)));
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getInt(1);
                }
            }
        }

        throw new SQLException("Unable to create show.");
    }

    // Seat selection from database bookings
    private void showSeats(Movie movie) {
        int showId;

        try {
            showId = getShowId(movie.id);
        } catch (SQLException e) {
            message(e.getMessage());
            return;
        }

        panel.removeAll();
        panel.setLayout(new BorderLayout(10, 10));

        JLabel heading = new JLabel(
                movie.title + " - SELECT YOUR SEAT",
                SwingConstants.CENTER);
        heading.setFont(new Font("Arial", Font.BOLD, 22));
        panel.add(heading, BorderLayout.NORTH);

        java.util.Set<String> bookedSeats = new java.util.HashSet<>();

        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT seat FROM bookings WHERE show_id = ?")) {

            ps.setInt(1, showId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bookedSeats.add(rs.getString("seat"));
                }
            }
        } catch (SQLException e) {
            message(e.getMessage());
            showHome();
            return;
        }

        JPanel seats = new JPanel(new GridLayout(5, 6, 8, 8));

        for (char row = 'A'; row <= 'E'; row++) {
            for (int col = 1; col <= 6; col++) {
                String seat = row + String.valueOf(col);
                JButton button = new JButton(seat);

                if (bookedSeats.contains(seat)) {
                    button.setBackground(Color.RED);
                    button.setForeground(Color.WHITE);
                    button.setEnabled(false);
                } else {
                    button.setBackground(Color.GREEN);
                    button.addActionListener(e ->
                            bookingDetails(movie, showId, seat));
                }

                seats.add(button);
            }
        }

        JLabel screen = new JLabel("SCREEN", SwingConstants.CENTER);
        screen.setOpaque(true);
        screen.setBackground(Color.DARK_GRAY);
        screen.setForeground(Color.WHITE);

        JPanel center = new JPanel(new BorderLayout(10, 10));
        center.setBorder(BorderFactory.createEmptyBorder(20, 40, 20, 40));
        center.add(screen, BorderLayout.NORTH);
        center.add(seats, BorderLayout.CENTER);

        panel.add(center, BorderLayout.CENTER);

        JButton back = new JButton("Back to Movies");
        back.addActionListener(e -> showHome());
        panel.add(back, BorderLayout.SOUTH);

        refresh();
    }

    // Save ticket, coupon and food details
    private void bookingDetails(Movie movie, int showId, String seat) {
        JTextField customer = new JTextField();
        JTextField coupon = new JTextField();

        Object[] fields = {
                "Movie: " + movie.title,
                "Seat: " + seat,
                "Ticket price: Rs. " + movie.price,
                "Customer name:", customer,
                "Coupon (WELCOME10):", coupon
        };

        if (JOptionPane.showConfirmDialog(
                this, fields, "Booking Details",
                JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) return;

        String name = customer.getText().trim();
        String code = coupon.getText().trim().toUpperCase();

        if (name.isEmpty()) {
            message("Enter customer name.");
            return;
        }

        double discount = 0;

        try (Connection con = connect()) {
            if (!code.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT discount FROM coupons WHERE code = ?")) {

                    ps.setString(1, code);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            message("Invalid coupon.");
                            return;
                        }

                        int percent = rs.getInt("discount");
                        if (percent < 0 || percent > 100) {
                            message("Invalid coupon discount.");
                            return;
                        }
                        discount = percent / 100.0;
                    }
                }
            }
        } catch (SQLException e) {
            message(e.getMessage());
            return;
        }

        String[] foodOptions = {
                "No Food", "Popcorn", "Burger", "Pizza", "Cold Drink"
        };

        String food = (String) JOptionPane.showInputDialog(
                this, "Select food:", "Food Ordering",
                JOptionPane.QUESTION_MESSAGE, null,
                foodOptions, foodOptions[0]);

        if (food == null) return;

        String quantity = "1";

        if (!food.equals("No Food")) {
            String[] quantities = {"1", "2", "3", "4", "5"};
            quantity = (String) JOptionPane.showInputDialog(
                    this, "Select quantity:", "Food Quantity",
                    JOptionPane.QUESTION_MESSAGE, null,
                    quantities, quantities[0]);

            if (quantity == null) return;
        }

        int qty = Integer.parseInt(quantity);
        double foodPrice = switch (food) {
            case "Popcorn" -> 80;
            case "Burger" -> 120;
            case "Pizza" -> 200;
            case "Cold Drink" -> 50;
            default -> 0;
        };

        double total = movie.price * (1 - discount)
                + foodPrice * qty;

        String ticketId = "TKT-" +
                UUID.randomUUID().toString().toUpperCase();

        String foodDescription = food.equals("No Food")
                ? food : food + " x " + qty;

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Customer: " + name
                        + "\nMovie: " + movie.title
                        + "\nSeat: " + seat
                        + "\nFood: " + foodDescription
                        + "\nTotal: Rs. " + String.format("%.2f", total)
                        + "\nTicket ID: " + ticketId
                        + "\n\nConfirm booking?",
                "Confirm Booking",
                JOptionPane.YES_NO_OPTION);

        if (confirm != JOptionPane.YES_OPTION) return;

        try (Connection con = connect()) {
            con.setAutoCommit(false);

            try {
                // Lock this show/seat reservation to avoid simultaneous
                // duplicate bookings from different application windows.
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT id FROM shows WHERE id = ? FOR UPDATE")) {
                    ps.setInt(1, showId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("Show not found.");
                        }
                    }
                }

                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT id FROM bookings WHERE show_id = ? AND seat = ?")) {
                    ps.setInt(1, showId);
                    ps.setString(2, seat);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new SQLException(
                                    "This seat has already been booked.");
                        }
                    }
                }

                String sql = """
                    INSERT INTO bookings
                    (ticket_id, customer, movie_id, show_id, seat,
                     amount, food)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;

                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setString(1, ticketId);
                    ps.setString(2, name);
                    ps.setInt(3, movie.id);
                    ps.setInt(4, showId);
                    ps.setString(5, seat);
                    ps.setDouble(6, total);
                    ps.setString(7, foodDescription);
                    ps.executeUpdate();
                }

                con.commit();

                JOptionPane.showMessageDialog(this,
                        "BOOKING SUCCESSFUL!\n\n"
                                + "Ticket ID: " + ticketId
                                + "\nCustomer: " + name
                                + "\nMovie: " + movie.title
                                + "\nSeat: " + seat
                                + "\nFood: " + foodDescription
                                + "\nTotal: Rs. "
                                + String.format("%.2f", total));

                showSeats(movie);

            } catch (Exception e) {
                con.rollback();
                message("Booking failed: " + e.getMessage());
            } finally {
                con.setAutoCommit(true);
            }

        } catch (SQLException e) {
            message("Database error: " + e.getMessage());
        }
    }

    // Admin login
    private void adminLogin() {
        JPasswordField password = new JPasswordField();

        int result = JOptionPane.showConfirmDialog(
                this, password, "Admin Login",
                JOptionPane.OK_CANCEL_OPTION);

        if (result != JOptionPane.OK_OPTION) return;

        if (!"admin123".equals(new String(password.getPassword()))) {
            message("Incorrect admin password.");
            return;
        }

        String[] options = {
                "Add Movie", "View Movies", "View Bookings", "Close"
        };

        while (true) {
            int choice = JOptionPane.showOptionDialog(
                    this, "Select Admin Action", "Admin Panel",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE, null,
                    options, options[0]);

            if (choice == 0) addMovie();
            else if (choice == 1) showHome();
            else if (choice == 2) viewBookings();
            else break;
        }
    }

    private void addMovie() {
        JTextField title = new JTextField();
        JTextField genre = new JTextField();
        JTextField price = new JTextField();

        Object[] fields = {
                "Movie title:", title,
                "Genre:", genre,
                "Price:", price
        };

        if (JOptionPane.showConfirmDialog(
                this, fields, "Add Movie",
                JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) return;

        try {
            double cost = Double.parseDouble(price.getText().trim());
            if (title.getText().trim().isEmpty() || cost <= 0) {
                message("Enter valid movie details.");
                return;
            }

            try (Connection con = connect();
                 PreparedStatement ps = con.prepareStatement(
                         "INSERT INTO movies(title, genre, price) VALUES (?, ?, ?)")) {

                ps.setString(1, title.getText().trim());
                ps.setString(2, genre.getText().trim());
                ps.setDouble(3, cost);
                ps.executeUpdate();
            }

            message("Movie added successfully.");
            showHome();

        } catch (Exception e) {
            message("Could not add movie: " + e.getMessage());
        }
    }

    private void viewBookings() {
        StringBuilder output = new StringBuilder();

        String sql = """
            SELECT b.ticket_id, b.customer, m.title, b.seat,
                   b.amount, b.food, s.show_time
            FROM bookings b
            JOIN movies m ON b.movie_id = m.id
            JOIN shows s ON b.show_id = s.id
            ORDER BY b.id DESC
            """;

        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                output.append("Ticket: ").append(rs.getString("ticket_id"))
                        .append("\nCustomer: ").append(rs.getString("customer"))
                        .append("\nMovie: ").append(rs.getString("title"))
                        .append("\nSeat: ").append(rs.getString("seat"))
                        .append("\nShow: ").append(rs.getTimestamp("show_time"))
                        .append("\nFood: ").append(rs.getString("food"))
                        .append("\nAmount: Rs. ").append(rs.getBigDecimal("amount"))
                        .append("\n-----------------------------\n");
            }

            if (output.isEmpty()) output.append("No bookings yet.");

            JTextArea area = new JTextArea(output.toString(), 20, 45);
            area.setEditable(false);

            JOptionPane.showMessageDialog(
                    this, new JScrollPane(area), "All Bookings",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (SQLException e) {
            message(e.getMessage());
        }
    }

    private void message(String text) {
        JOptionPane.showMessageDialog(this, text);
    }

    private void refresh() {
        panel.revalidate();
        panel.repaint();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() ->
                new SmartTheatre().setVisible(true));
    }
}