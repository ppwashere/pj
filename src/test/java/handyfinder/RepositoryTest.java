package handyfinder;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;

import com.qwefgh90.io.handyfinder.gui.AppStartupConfig;
import com.qwefgh90.io.handyfinder.springweb.RootContext;
import com.qwefgh90.io.handyfinder.springweb.ServletContextTest;
import com.qwefgh90.io.handyfinder.springweb.model.Directory;
import com.qwefgh90.io.handyfinder.springweb.repository.MetaRespository;

@RunWith(SpringJUnit4ClassRunner.class)
@WebAppConfiguration
// ApplicationContext will be loaded from
// "classpath:/com/example/OrderServiceTest-context.xml"
@ContextConfiguration(classes = { ServletContextTest.class, RootContext.class })
public class RepositoryTest {
	@Autowired
	MetaRespository indexProperty;
	Directory dir1;
	Directory dir2;
	List list = new ArrayList();

	static {
		try {
			AppStartupConfig.initializeEnv(null);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	@Before
	public void setup() throws SQLException, IOException {
		indexProperty.dropDirectoryTable();
		indexProperty.createDirectoryTable();

		dir1 = new Directory();
		dir1.setPathString("path");
		dir1.setUsed(true);
		dir1.setRecusively(false);
		dir2 = new Directory();
		dir2.setPathString("path");
		dir2.setUsed(true);
		dir2.setRecusively(false);
		list.add(dir1);
		list.add(dir2);
	}
	
	@After
	public void clean() throws SQLException{
		indexProperty.dropDirectoryTable();
		
	}

	@Test
	public void directoryTest1() throws SQLException {
		Directory dir = new Directory();
		dir.setPathString("path");
		dir.setUsed(true);
		dir.setRecusively(false);
		indexProperty.saveOne(dir);
		assertTrue(indexProperty.selectDirectory().size() == 1);
		indexProperty.deleteOne(dir);
		assertTrue(indexProperty.selectDirectory().size() == 0);
	}

	@Test
	public void directoryTest2() throws SQLException {
		indexProperty.save(list);
		assertTrue(indexProperty.selectDirectory().size() == 2);

		indexProperty.deleteDirectories();
		assertTrue(indexProperty.selectDirectory().size() == 0);
	}
}
