import java.sql.*;
import java.security.MessageDigest;
import java.util.Scanner;

public class BankingApp {

    private static final String URL = "jdbc:mysql://localhost:3306/banking_system";
    private static final String USER = "root"; // your MySQL username
    private static final String PASS = "Nicky@2004"; // your MySQL password

    private static Connection conn;

    public static void main(String[] args) {
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
            Scanner sc = new Scanner(System.in);

            while(true) {
                System.out.println("\n=== MyBank ===");
                System.out.println("1. Register");
                System.out.println("2. Login");
                System.out.println("3. Exit");
                System.out.print("Choose an option: ");
                int choice = sc.nextInt();
                sc.nextLine(); // consume newline

                switch(choice) {
                    case 1 -> register(sc);
                    case 2 -> login(sc);
                    case 3 -> { System.out.println("Goodbye!"); sc.close(); conn.close(); System.exit(0);}
                    default -> System.out.println("Invalid choice!");
                }
            }
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }

    // Hashing passwords using SHA-256
    private static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for(byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if(hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Registration
    private static void register(Scanner sc) {
        try {
            System.out.print("Enter username: ");
            String username = sc.nextLine();
            System.out.print("Enter password: ");
            String password = sc.nextLine();
            String hashedPassword = hashPassword(password);

            String sql = "INSERT INTO users (username, password_hash) VALUES (?, ?)";
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, hashedPassword);
            ps.executeUpdate();

            ResultSet rs = ps.getGeneratedKeys();
            if(rs.next()) {
                int userId = rs.getInt(1);
                // Create account with 0 balance
                String accSql = "INSERT INTO accounts (user_id, balance) VALUES (?, ?)";
                PreparedStatement psAcc = conn.prepareStatement(accSql);
                psAcc.setInt(1, userId);
                psAcc.setDouble(2, 0);
                psAcc.executeUpdate();
            }

            System.out.println("Registration successful!");
        } catch(SQLException e) {
            System.out.println("Username already exists!");
        }
    }

    // Login
    private static void login(Scanner sc) {
        try {
            System.out.print("Username: ");
            String username = sc.nextLine();
            System.out.print("Password: ");
            String password = sc.nextLine();
            String hashedPassword = hashPassword(password);

            String sql = "SELECT user_id FROM users WHERE username=? AND password_hash=?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            ps.setString(2, hashedPassword);
            ResultSet rs = ps.executeQuery();

            if(rs.next()) {
                int userId = rs.getInt("user_id");
                System.out.println("Login successful!");
                userMenu(sc, userId);
            } else {
                System.out.println("Invalid credentials!");
            }
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }

    // User Menu after login
    private static void userMenu(Scanner sc, int userId) {
        try {
            while(true) {
                System.out.println("\n1. Check Balance");
                System.out.println("2. Transfer Money");
                System.out.println("3. Logout");
                System.out.print("Choose: ");
                int choice = sc.nextInt();
                sc.nextLine();

                switch(choice) {
                    case 1 -> checkBalance(userId);
                    case 2 -> transferMoney(sc, userId);
                    case 3 -> { System.out.println("Logged out!"); return; }
                    default -> System.out.println("Invalid choice!");
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    private static void checkBalance(int userId) throws SQLException {
        String sql = "SELECT balance FROM accounts WHERE user_id=?";
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setInt(1, userId);
        ResultSet rs = ps.executeQuery();
        if(rs.next()) {
            System.out.println("Your balance: " + rs.getDouble("balance"));
        }
    }

    private static void transferMoney(Scanner sc, int userId) throws SQLException {
        System.out.print("Enter recipient account ID: ");
        int toAccount = sc.nextInt();
        System.out.print("Enter amount to transfer: ");
        double amount = sc.nextDouble();

        conn.setAutoCommit(false); // start transaction

        try {
            // Get sender account
            String sqlFrom = "SELECT account_id, balance FROM accounts WHERE user_id=? FOR UPDATE";
            PreparedStatement psFrom = conn.prepareStatement(sqlFrom);
            psFrom.setInt(1, userId);
            ResultSet rsFrom = psFrom.executeQuery();
            if(!rsFrom.next()) {
                System.out.println("Your account not found!");
                conn.rollback();
                return;
            }
            int fromAccount = rsFrom.getInt("account_id");
            double fromBalance = rsFrom.getDouble("balance");

            if(fromBalance < amount) {
                System.out.println("Insufficient balance!");
                conn.rollback();
                return;
            }

            // Get recipient
            String sqlTo = "SELECT balance FROM accounts WHERE account_id=? FOR UPDATE";
            PreparedStatement psTo = conn.prepareStatement(sqlTo);
            psTo.setInt(1, toAccount);
            ResultSet rsTo = psTo.executeQuery();
            if(!rsTo.next()) {
                System.out.println("Recipient account not found!");
                conn.rollback();
                return;
            }

            double toBalance = rsTo.getDouble("balance");

            // Update balances
            String updateFrom = "UPDATE accounts SET balance=? WHERE account_id=?";
            PreparedStatement psUpdateFrom = conn.prepareStatement(updateFrom);
            psUpdateFrom.setDouble(1, fromBalance - amount);
            psUpdateFrom.setInt(2, fromAccount);
            psUpdateFrom.executeUpdate();

            String updateTo = "UPDATE accounts SET balance=? WHERE account_id=?";
            PreparedStatement psUpdateTo = conn.prepareStatement(updateTo);
            psUpdateTo.setDouble(1, toBalance + amount);
            psUpdateTo.setInt(2, toAccount);
            psUpdateTo.executeUpdate();

            // Record transaction
            String txSql = "INSERT INTO transactions (account_from, account_to, amount) VALUES (?, ?, ?)";
            PreparedStatement psTx = conn.prepareStatement(txSql);
            psTx.setInt(1, fromAccount);
            psTx.setInt(2, toAccount);
            psTx.setDouble(3, amount);
            psTx.executeUpdate();

            conn.commit();
            System.out.println("Transfer successful!");

        } catch(SQLException e) {
            conn.rollback();
            e.printStackTrace();
        } finally {
            conn.setAutoCommit(true);
        }
    }
}
