package com.stuartmathews.endofday;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.springframework.boot.Banner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;


/*
 * Manual:
 * public @interface SpringBootApplication
 * Indicates a configuration class that declares one or more @Bean methods and also triggers auto-configuration and
 * component scanning. This is a convenience annotation that is equivalent to declaring @Configuration,
 * @EnableAutoConfiguration and @ComponentScan.
 *
 */
@SpringBootApplication
public class Application
{
   final public static Object errorLogLock = new Object();
   public static void main(String[] args) throws Exception
   {

      ConfigurableApplicationContext context = new SpringApplicationBuilder()
              .sources(Application.class)
              .bannerMode(Banner.Mode.OFF)
              .run(args);

      Application app = context.getBean(Application.class);
      Environment env = context.getBean(Environment.class);
      FileWriter errorLog = new FileWriter(env.getProperty("error.outputfile"));
      try 
      {           
         app.start(args, env, errorLog);
         errorLog.close();
      }
      catch(Exception e)
      {
         System.out.printf("Unknown exception occured:'%s'\n", e.getMessage());
      }
      finally
      {
         errorLog.close();
      }
   }

   private void start(String[] args, Environment env, FileWriter errorLog) throws Exception
   {
      System.out.println(Arrays.toString(args));
      /*
      Program defaults:
      company.filename=companylist.csv
      output.filename=EOD.csv
      output.datefmt=yyyy-MM-dd HH-mm-ss
      company.excludefile=exclude.csv
      company.query=http://d.yimg.com/aq/autoc?query=%s&region=%s&lang=%s
      company.resolve=true
      query.region=UK
      query.lang=en-GB
      */
      if (args.length == 0)
      {
         System.out.printf("Usage: java EndOfDay.jar [args]\n"
                 + "args:\n"
                 + "--company.filename=companylist.csv\n"
                 + "--company.excludefile=excludefile.csv\n"
                 + "--output.filename=EOD.csv\n"
                 + "--output.datefmt=yyyy-MM-dd HH-mm-ss\n"
                 + "--resolve.company=true|false\n"
                 + "--query.region=UK\n"
                 + "--query.lang=en-US\n");
         return;
      }
      
      try
      {
         String csv = GenerateEndOfDayCSV(GetStringFromFileContents(env.getProperty("company.filename"), Charset.defaultCharset()), env, errorLog);
      
         // Write result to output file      
         String filename = env.getProperty("output.filename");
         if (filename.equalsIgnoreCase("EOD.csv"))
         {
            Date date = new java.util.Date();
            SimpleDateFormat dateFormat = new SimpleDateFormat(env.getProperty("output.datefmt"));
            filename = "EndOfDayPrices_" + dateFormat.format(date) + ".csv";
            filename = filename.replace(' ', '_');
         }// otherwise use provided filename...         

         try (PrintWriter out = new PrintWriter(filename))
         {
            out.print(csv);
            System.out.printf("Generated output file '%s'.\n", filename);
         } catch (Exception e)
         {
            String fmt = String.format("Error writing to output file '%s'. Error message='%s'\n", filename, e.getMessage());
            System.out.printf(fmt);
         }
      }
      catch(IOException io)
      {
         String fmt = String.format("IO Error occured while calling GenerateEndOfDayCSV(): '%s'.\n", io.getMessage());
         System.out.printf(fmt);         
         errorLog.append(fmt);
      }
      catch(Exception ex)
      {
         String fmt = String.format("Unknown error type occured while calling GenerateEndOfDayCSV(), message: '%s'.\n", ex.getMessage());
         System.out.printf(fmt);
         errorLog.append(fmt);
      }
   }

   static String GetStringFromFileContents(String path, Charset encoding) throws IOException
   {
      try
      {
         byte[] encoded = Files.readAllBytes(Paths.get(path));
         return new String(encoded, encoding);
      }
      catch(IOException io)
      {
        System.out.printf("Error while trying to read contents of file '%s'\n",path); 
        throw io;
      }
   }

   static String convertStreamToString(java.io.InputStream is)
   {
      java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
      return s.hasNext() ? s.next() : "";
   }
   
   final static Object QuotesLock = new Object();

   /***
    * Returns all the stock details for the companies provided in company file 
    * @param csv
    * @param env
    * @return stock company end of day quote results as csv
    * @throws Exception 
    */
   public static String GenerateEndOfDayCSV(String csv, Environment env, FileWriter errorLog) throws Exception
   {
      // Keep track of our data
      HashMap<String, TickerDetailsQuote> Quotes = new HashMap<>();
      ArrayList<String> excludedCompanies;
      
      
      System.out.printf("using company file '%s'\n", env.getProperty("company.filename"));
      String[] companyFileLines = csv.split(System.getProperty("line.separator"));
      // Fetch companies to excluded
      excludedCompanies = GetExcludedCompanies(env.getProperty("company.excludefile"));

      int lineCount = 0;
      Collection<Thread> threads = new ArrayList<>();      
      
      for (String line : companyFileLines)
      {
         if(threads.size() != MAX_THREADS){ 
             System.out.printf("Starting new thread\n");
             Thread thread = new Thread(() -> {
                try {
                    DoRequestForCompany(line, excludedCompanies, env, errorLog, Quotes, lineCount);                                        
                } catch (IOException ex) {                    
                    Logger.getLogger(Application.class.getName()).log(Level.SEVERE, null, ex);
                }
            });
             
            threads.add(thread);
            thread.start();
         }
         else
         {
            System.out.printf("Thread queue maxed out. need to remove a finished thread..\n");
            boolean anyFree = false;
            for(Thread thread : threads){
                if(!thread.isAlive()){
                    System.out.printf("Thread finished, removing it\n");
                    threads.remove(thread);
                    anyFree = true;
                }
            }
            threads.iterator().next().join();
         }
                     
      }
      // Convert the tracked quote data list into a CSV string and return to caller
      return ObjectListToCSV.convertListToCSV(new ArrayList<TickerDetailsQuote>(Quotes.values()));
   }
    private static final int MAX_THREADS = 2;

    private static boolean DoRequestForCompany(String line, ArrayList<String> excludedCompanies, Environment env, FileWriter errorLog, HashMap<String, TickerDetailsQuote> Quotes, int lineCount) throws IOException 
    {
        if (line.isEmpty()) {
            return true;
        }
        String[] values = line.split(",");
        String company = values[0];
        if (!company.isEmpty()) {
            if (excludedCompanies.contains(company)) {
                System.out.printf("Excluding company '%s'\n", company);
                return true;
            }
            // used to make HTTP requests
            HttpClient web = new HttpClient();
            // The company name might have spaces so need to encode if going to pass as URL data
            String companyName = URLEncoder.encode(company, "UTF-8").replace("+", "%20");
            // Try resolve the company name to a ticker symbol                         
            String region = env.getProperty("query.region");
            String lang = env.getProperty("query.lang");
            String request = String.format(env.getProperty("company.query"), companyName, region, lang);
            String response = "";
            String ticker = "";
            try {
                // Resolve company names to ticker synbols?
                if (env.getProperty("company.resolve", boolean.class)) {
                    try {
                        // yes...
                        HttpMethod method = new GetMethod(request);
                        web.executeMethod(method);
                        response = convertStreamToString(method.getResponseBodyAsStream());
                        method.releaseConnection();
                        // Deserialize response
                        NameLookupResult nameResult = new Gson().fromJson(response, NameLookupResult.class);
                        // Ignore bad response data and continue to next company
                        if (nameResult.getResultSet() == null || 
                                nameResult.getResultSet().getResult() == null ||
                                nameResult.getResultSet().getResult().isEmpty() ||
                                nameResult.getResultSet().getResult().isEmpty()) {
                            String fmt = String.format("Ignoring bad response data for company '%s'. Skipping company\n", company);
                            System.out.printf(fmt);
                            synchronized(errorLogLock){
                                //errorLog.append(fmt);
                                fmt = String.format("Dad data:\n'%s'\n", response);
                                System.out.printf(fmt);
                                //errorLog.append(fmt);
                            }
                            return true;
                        }
                        // Store resolved compnay to ticker symbol for later on...
                        ticker = nameResult.getResultSet().getResult().get(0).getsymbol();
                    }catch (IOException | JsonSyntaxException e)
                  {
                      String fmt = String.format("Error while resolving company name '%s': %s\n", company, e.getMessage());
                      System.out.printf(fmt);
                      synchronized(errorLogLock){ errorLog.append(fmt); }
                  }
                } else {
                    // Ticker symbol is the company
                    ticker = company;
                }
                try {
                    // Now get stock prices for this ticker symbol...
                    request = "https://query.yahooapis.com/v1/public/yql?q=select%20*%20from%20yahoo.finance.quotes%20where%20symbol%20in%20(%22" + ticker + "%22)&format=json&env=store%3A%2F%2Fdatatables.org%2Falltableswithkeys&callback=";
                    HttpMethod method = new GetMethod(request);
                    web.executeMethod(method);
                    response = convertStreamToString(method.getResponseBodyAsStream());
                    method.releaseConnection();
                    // Deserialize the response
                    TickerDetailsRootObject tickerResult = new Gson().fromJson(response, TickerDetailsRootObject.class);
                    // Ignore bad response data
                    if (tickerResult.getquery().getcount() == 0) {
                        String fmt = String.format("Ignoring bad response data for quote for company '%s'. Skipping company....\n", company);
                        System.out.printf(fmt);
                        //synchronized(errorLogLock){ errorLog.append(fmt); }
                        return true;
                    }
                    // Save the ticker stock details
                    TickerDetailsQuote quote = tickerResult.getquery().getresults().getquote();
                    // Alwasys ensure that the company name is set
                    quote.setFullName(company);
                    synchronized(QuotesLock){
                        // Store ticker quote pe rcompany if we dont know about it yet.
                        if (!Quotes.containsKey(company)) {
                            Quotes.put(company, quote);
                            // Increase how many companies we've queried so far 
                            lineCount++;
                        }
                    }
                  // Out put to show we're doing something.
                  System.out.printf("%d-Company=''Ticker='%s', Ask='%s'\n",
                          lineCount,
                          ticker,
                          tickerResult.getquery().getresults().getquote().getLastTradePriceOnly());
                }catch (IOException | JsonSyntaxException e)
               {
                   String fmt = String.format("Error while getting ticker quote details for'%s': %s\n", ticker, e.getMessage());
                   System.out.printf(fmt);
                   //synchronized(errorLogLock){ errorLog.append(fmt); }
               }
            }catch (Exception e)
            {
                String fmt = String.format("Unknown error occured: '%s'", e.getMessage());
                System.out.printf(fmt);
                //synchronized(errorLogLock){ errorLog.append(fmt); }
            }
        }
        return false;
    }
    
    private static ArrayList<String> GetExcludedCompanies(String filename) throws IOException
   {
      ArrayList<String> excludedCompanies = new ArrayList<>();
      File f = new File(filename);
      
      if(!f.exists() && !f.isDirectory()) { 
         return excludedCompanies;
      }
      
      System.out.printf("Using exclude file '%s'...\n", filename);
      
      String[] excludedFileLines = GetStringFromFileContents(filename,Charset.defaultCharset()).split(System.getProperty("line.separator"));
      for (String line : excludedFileLines)
      {
         if (line.isEmpty())
         {
            continue;
         }

         String[] values = line.split(",");
         String company = values[0];
         if (!company.isEmpty() && !excludedCompanies.contains(company))
         {
            excludedCompanies.add(company);
         }         
      }
      return excludedCompanies;
   }
}