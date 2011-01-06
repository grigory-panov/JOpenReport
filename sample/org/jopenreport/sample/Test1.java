package org.jopenreport.sample;

import org.jopenreport.ReportGenerator;

public class Test1 {

	public static void main(String[] args ){
		runSampleTest();
	}
	

	public static int runSampleTest(){
		ReportGenerator generator = new ReportGenerator();
		if(generator.open("test.odt")){
			System.out.println("open file 'test.odt' - ok");
			// replace fields
			generator.setValue(generator.PARAM, "value for field1");
			generator.exec("tag1");
			
			generator.setValue(generator.PARAM, "value for field2");
			generator.exec("tag2");

			generator.setValue(generator.PARAM, "very very very long value for field3. I don't know what to do. Keep in line this long text. Arrrgh! ");
			generator.exec("tag3");
			
			// populating table section
			generator.setValue("field1", "test");
			generator.setValue("field2", "another column");
			generator.setValue("field3", "third column value");

			generator.exec("table");

			generator.setValue("field1", "second row");
			generator.setValue("field2", "second another column");
			generator.setValue("field3", "second row - third column");
			generator.exec("table");
			
			// clean up
			generator.cleanUpTags();
			
			if(!generator.save("output.odt")){
				System.out.println("cannot save file 'output.odt'");
				return -1;
			}else{
				System.out.println("save file 'output.odt'- ok");
			}
		}else{
			System.out.println("cannot open 'test.odt'");
			return -1;
		}
		return 0;
	}

}
