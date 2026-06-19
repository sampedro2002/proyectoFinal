import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class FixFlyway {
    public static void main(String[] args) {
        try {
            Connection conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/control_eat_food?user=admin&password=BN2002sg");
            Statement stmt = conn.createStatement();
            stmt.executeUpdate("DELETE FROM fingerprint");
            System.out.println("Huellas corruptas eliminadas de la base de datos.");
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
