package hu.barbar.cryptoTrader;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;

import org.codehaus.plexus.configuration.processor.ConfigurationResourceHandler;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.account.FundingRecord;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.exceptions.NotAvailableFromExchangeException;
import org.knowm.xchange.exceptions.NotYetImplementedForExchangeException;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.service.account.AccountService;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.HistoryParamsFundingType;
import org.knowm.xchange.service.trade.params.TradeHistoryParamCurrencies;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.knowm.xchange.service.trade.params.TradeHistoryParamsTimeSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hu.barbar.confighandler.JsonConfigHandler;
import hu.barbar.util.FileHandler;

public class SmartSeller {

	private static final String configSourceJSONPath = "c:/Tools/kr_config.json";
	
	final static Logger logger = LoggerFactory.getLogger(SmartSeller.class);
	
	private long DEFAULT_DELAY_IN_MS = 30000;
	
	private static final String DEFAULT_DATA_FILE_EXTENSION = "csv";
	
	private static final String DEFAULT_SEPARATOR_IN_DATA_FILE = ";";
	
	private static final String DEFAULT_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";
	
	private static final String DEFAULT_TIMESTAMP_FORMAT_IN_FILENAME = "yyyy-MM-dd_HH-mm-ss";

	private static final int EXPECTED_ARGUMENT_COUNT = 3;

	private JsonConfigHandler config = null;
	
	private static String logFile = null;
	
	private String dataFile = null;
	
	private String separatorInDataFile = null;
	
	private static SimpleDateFormat sdf = null;
	
	private static SmartSeller me;
	
	private static int errorCounter = 0;
	
	private BigDecimal amount = null;
	
	private String currency = null;
	
	private CurrencyPair usedCurrencyPair = null;
	
	private BigDecimal stopPrice = null;
	
	private BigDecimal lastPrice = null;
	
	private BigDecimal currentPrice = null;
	
	private BigDecimal sellMargin = null;
	
	//TODO: remove static modifier later..
	private static Exchange krakenExchange = null;

	//TODO: remove static modifier later..
	private static MarketDataService marketDataService = null;
	
	
	public SmartSeller(String[] args){
		
		readConfig();
		processParams(args);
		sdf = new SimpleDateFormat(config.getString("dateformat", DEFAULT_TIMESTAMP_FORMAT));

		initExchange();

		usedCurrencyPair = getUSDBasedCurrencyPairFor(currency);
		
		// Get initialPrice from Kraken.
		long before, after;
		before = System.currentTimeMillis();
		lastPrice = getPriceOf(usedCurrencyPair);
		after = System.currentTimeMillis();
		log("Elasped time for get current price: " + (after - before) + " ms\n");
		if(lastPrice == null){
			System.exit(2);
		}
		
		BigDecimal currentlyAvailableBalance = this.getAvailableBalanceFor(this.currency);
		
		sellMargin = lastPrice.subtract(stopPrice);
		
		// Show parameters
		log("Initial price:      \t" + lastPrice + " USD / " + this.currency);
		log("Initial stop limit: \t" + stopPrice + " USD / " + this.currency);
		log("Sell margin:        \t" + sellMargin + " USD");
		log("Amount:             \t" + (lessThen(this.amount,0)?"All available":this.amount) + " " + this.currency);
		log("Available balance:  \t" + currentlyAvailableBalance + " " + this.currency);
		log("Minimum income:     \t" + ((lessThen(this.amount,0)?currentlyAvailableBalance:this.amount).multiply(this.stopPrice)).setScale(2, BigDecimal.ROUND_HALF_UP) + " USD");
		log("Current value:      \t" + ((lessThen(this.amount,0)?currentlyAvailableBalance:this.amount).multiply(this.lastPrice)).setScale(2, BigDecimal.ROUND_HALF_UP) + " USD");
		log("Maximum loss:       \t" + ((lessThen(this.amount,0)?currentlyAvailableBalance:this.amount).multiply(this.lastPrice).subtract((lessThen(this.amount,0)?currentlyAvailableBalance:this.amount).multiply(this.stopPrice)) ).setScale(2, BigDecimal.ROUND_HALF_UP) + " USD");
		log("\n");
		
		// Check parameters and exit if invalid
		if(stopPrice.compareTo(lastPrice) >= 0){
			//TODO log
			log("ERROR! Inital stop price is NOT lower then initial price!");
			//TODO: create an "instant sell" option for this case..
			System.exit(3);
		}
		
		currentPrice = lastPrice.add(BigDecimal.ZERO);
		
	}
	
	private void readConfig(){
		config = new JsonConfigHandler(configSourceJSONPath);
		
		logFile = config.getString("logfile", null);
		
		String dataFileName = config.getString("datafile.name of data file", null);
		if(dataFileName != null && dataFileName.trim().equals("")){
			dataFileName = null;
		}
		SimpleDateFormat sdfForDataFileName = new SimpleDateFormat(config.getString("datafile.timestamp format in filename", DEFAULT_TIMESTAMP_FORMAT_IN_FILENAME));
		String dataFileExt = config.getString("datafile.extension", DEFAULT_DATA_FILE_EXTENSION);
		
		if(dataFileName != null){
			// Data logging is enabled
			this.dataFile = dataFileName + sdfForDataFileName.format(new Date()) + "." + dataFileExt;
			this.separatorInDataFile = config.getString("datafile.separator", DEFAULT_SEPARATOR_IN_DATA_FILE);
			//Write the header line into datafile.. 
			FileHandler.appendToFile(this.dataFile, "Date" + separatorInDataFile + "Price" + separatorInDataFile + "StopLimit" + separatorInDataFile + "Margin" + separatorInDataFile + "Diff");
		}else{
			// It means data logging is "disabled"
			this.dataFile = null;
		}
		
	}
	
	private void doTheJob(){
		
		// Store the previous price
		lastPrice = currentPrice.add(BigDecimal.ZERO);
		
		while(true){
			
			// Update current price
			currentPrice = getPriceOf(usedCurrencyPair);
			
			// Price increasing >> need to increase stopPrice
			if(currentPrice.compareTo( (stopPrice.add(sellMargin)) ) > 0){
				stopPrice = currentPrice.subtract(sellMargin);
			}
			// Store the previous price
			lastPrice = currentPrice.add(BigDecimal.ZERO);
			
			showCurrentValues();
			storeValues();
			
			// Check if stopPrice is bigger then currentPrice >> need to sell..
			if(stopPrice.compareTo(currentPrice) > 0){
				log("\nPrice is lower then stop price!\nNeed to sell!!!");
				System.exit(0);
			}
			
			try {
				Thread.sleep(config.getLong("delay_in_ms", DEFAULT_DELAY_IN_MS));
			} catch (InterruptedException e) {
				errorCounter++;
				e.printStackTrace();
				if(errorCounter > 4){
					System.exit(5);
				}
			}
		}
		
	}
	
	/**
	 * Show line in console output <br>
	 * and write it into the specified log file if it has been specified in config Json.
	 * @param line
	 */
	private static void log(String line){
		if(logFile != null){
			FileHandler.appendToFile(logFile, line);
		}
		System.out.println(line);
	}
	
	private void showCurrentValues(){
		
		BigDecimal diff = currentPrice.subtract(stopPrice);
		
		String line = sdf.format(new Date());
		
		line += "\tCurr: "   + currentPrice.setScale(3, BigDecimal.ROUND_HALF_UP);
		line += "\tStop: "   + stopPrice.setScale(3, BigDecimal.ROUND_HALF_UP);
		line += "\tMargin: " + sellMargin.setScale(3, BigDecimal.ROUND_HALF_UP);
		line += "\tDiff: "   + diff.setScale(3, BigDecimal.ROUND_HALF_UP);
		
		log(line);
	}
	
	private void storeValues(){
		if(this.dataFile != null){
			BigDecimal diff = currentPrice.subtract(stopPrice);
			String line = sdf.format(new Date());
			line += separatorInDataFile + currentPrice;
			line += separatorInDataFile + stopPrice;
			line += separatorInDataFile + sellMargin;
			line += separatorInDataFile + diff;
			FileHandler.appendToFile(this.dataFile, line);
		}
	}
	
	private boolean lessThen(BigDecimal value, int compareValue){
		return value.compareTo(new BigDecimal(compareValue)) < 0;
	}
	
	
	private BigDecimal getAvailableBalanceFor(String currencyShortName){
		if(currencyShortName == null || currencyShortName.trim().equals("")){
			//TODO: handle this case
			return null;
		}
		Balance balance = getBalanceFor(currencyShortName); 
		if(balance == null){
			//TODO: handle this case
			return null;
		}
		return balance.getAvailable();
	}
	
	private Balance getBalanceFor(String currencyShortName){
		if(currencyShortName == null || currencyShortName.trim().equals("")){
			//TODO: handle this case
			return null;
		}
		AccountInfo accountInfo;
		try {
			accountInfo = krakenExchange.getAccountService().getAccountInfo();
		} catch (IOException e) {
			//TODO
			e.printStackTrace();
			return null;
		}
		Map<String, Wallet> wallets = accountInfo.getWallets();

		Iterator it = wallets.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry pair = (Map.Entry) it.next();
			// log("Wallet: " + pair.getKey() + " => " + pair.getValue());
			Wallet wallet = (Wallet) pair.getValue();

			Map<Currency, Balance> balances = wallet.getBalances();
			Iterator it2 = balances.entrySet().iterator();
			while (it2.hasNext()) {
				Map.Entry pair2 = (Map.Entry) it2.next();
				if(pair2.getKey().toString().trim().equalsIgnoreCase(currencyShortName.trim())){
					return (Balance) pair2.getValue();
				}
			}
		}
		
		return null;
	}

	
	private void initExchange(){
		krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class.getName());
		// Interested in the public market data feed (no authentication)
		marketDataService = krakenExchange.getMarketDataService();
		
		// krakenExchange = getExchangeForUser()
		krakenExchange.getExchangeSpecification().setApiKey(config.getString("api-key.appKey"));
		krakenExchange.getExchangeSpecification().setSecretKey(config.getString("api-key.privateKey"));
		krakenExchange.getExchangeSpecification().setUserName(config.getString("api-key.username"));
		krakenExchange.applySpecification(krakenExchange.getExchangeSpecification());
	}
	
	
	private void processParams(String[] args){
		log("Args size: " + args.length);
		if(args.length < EXPECTED_ARGUMENT_COUNT){
			log("Too few arguments.\nExample useage:"
					+ "SmartSeller.jar "
					+ "0.1 ETH 340.17"
					+ "");
			System.exit(1);
		}
		
		amount = new BigDecimal(args[0].replaceAll(",", ""));
		currency = args[1];
		stopPrice = new BigDecimal(args[2].replaceAll(",", ""));
		
		
		log("Amount: " + amount + " " + currency);
	}
	
	
	/**
	 * Return the last price of specified currency pair OR <br>
	 * <b>null</b> in case of exception caught.
	 * @param currencyPair
	 * @return
	 */
	private BigDecimal getPriceOf(CurrencyPair currencyPair){
		
		if(currencyPair == null){
			//TODO log..
			log("Can not get price of currencyPair without specified currencyPair (it was NULL).");
			return null;
		}
		
		BigDecimal lastPrice = null;
		
		// Get the latest ticker data showing price of specified currency pair.
		Ticker ticker;
		try {
			
			ticker = marketDataService.getTicker(currencyPair);
			lastPrice = ticker.getLast();

		} catch (NotAvailableFromExchangeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ExchangeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return lastPrice;
	}

	
	private static CurrencyPair getUSDBasedCurrencyPairFor(String shortCoinName){
		if(shortCoinName == null || shortCoinName.trim().equals("")){
			//TODO log
			log("Can not create USD based CurrencyPair object for: |" + shortCoinName + "|.");
			return null;
		}
		
		if(shortCoinName.equalsIgnoreCase("BTC")){
			return CurrencyPair.BTC_USD;
		}
		
		if(shortCoinName.equalsIgnoreCase("ETH")){
			return CurrencyPair.ETH_USD;
		}
		
		if(shortCoinName.equalsIgnoreCase("BCH")){
			return CurrencyPair.BCH_USD;
		}
		
		if(shortCoinName.equalsIgnoreCase("XMR")){
			return CurrencyPair.XMR_USD;
		}
		
		if(shortCoinName.equalsIgnoreCase("XRP")){
			return CurrencyPair.XRP_USD;
		}
		
		//TODO log
		log("ERROR: Can not get CurrencyPair object for |" + shortCoinName + "|.");
		return null;
	}
	
	/**
	 * Create Selling order with specified parameters
	 * @param tradeableAmount
	 * @param currencyPair
	 * @return the OrderID of created order if it could be created OR <br>
	 * <b>null</b> if there were any problem..
	 */
	private String createSellOrderFor(BigDecimal tradeableAmount, CurrencyPair currencyPair){

		// Interested in the private trading functionality (authentication) 
		TradeService tradeService = krakenExchange.getTradeService();
		
		// Create a marketOrder with specified parameters 
		MarketOrder marketOrder = new MarketOrder(OrderType.ASK, tradeableAmount, currencyPair);
		
		String orderID = null;
		try {
			
			orderID = tradeService.placeMarketOrder(marketOrder);
			log("Order created with ID: " + orderID);
			
		} catch (NotAvailableFromExchangeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NotYetImplementedForExchangeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ExchangeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return orderID;
	}
	
	
	// amount shortCoinName initialStopPrice
	// 0.01   ETH           330.17
	public static void main(String[] args) {
		
		me = new SmartSeller(args);
		
		me.doTheJob();
		
		/*

		// Get the latest ticker data showing BTC to USD
		Ticker ticker;
		try {
			ticker = marketDataService.getTicker(CurrencyPair.BTC_USD);
			log("Ticker: " + ticker.toString());
			log("Currency: " + Currency.USD);
			log("Last: " + ticker.getLast().toString());
			// log("Volume: " + ticker.getVolume().toString());
			// log("High: " + ticker.getHigh().toString());
			// log("Low: " + ticker.getLow().toString());

		} catch (NotAvailableFromExchangeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ExchangeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// Interested in the private trading functionality (authentication)
		TradeService tradeService = krakenExchange.getTradeService();
		// Get the open orders
		try {

			OpenOrders openOrders = tradeService.getOpenOrders();
			if (openOrders.getOpenOrders().isEmpty()) {
				log("There are no open orders.");
			} else {
				log(openOrders.toString());
			}
			
			
		} catch (NotAvailableFromExchangeException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (NotYetImplementedForExchangeException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (ExchangeException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		
		/*
		try {
			AccountInfo accountInfo = krakenExchange.getAccountService().getAccountInfo();
			log("\n\nAccount Info: " + accountInfo.toString() + "\n\n");
			Map<String, Wallet> wallets = accountInfo.getWallets();

			Iterator it = wallets.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry pair = (Map.Entry) it.next();
				// log("Wallet: " + pair.getKey() + " => " + pair.getValue());
				Wallet wallet = (Wallet) pair.getValue();

				Map<Currency, Balance> balances = wallet.getBalances();
				log("Balances: " + balances + "\n-\n");
				Iterator it2 = balances.entrySet().iterator();
				while (it2.hasNext()) {
					Map.Entry pair2 = (Map.Entry) it2.next();
					log(pair2.getKey() + ": " + ((Balance) pair2.getValue()).getTotal().toPlainString());
				}
			}
			

			 // Order

			 // // Interested in the private trading functionality
			 // (authentication) TradeService tradeService =
			 // krakenExchange.getTradeService();
			 // 
			 // // place a marketOrder with volume 0.01 OrderType orderType =
			 // (OrderType.ASK); BigDecimal tradeableAmount = new
			 // BigDecimal("0.15");
			 // 
			 // MarketOrder marketOrder = new MarketOrder(orderType,
			 // tradeableAmount, CurrencyPair.ETH_USD);
			 // 
			 // String orderID = tradeService.placeMarketOrder(marketOrder);
			 // log("Market Order ID: " + orderID); 
			 

		} catch (NotAvailableFromExchangeException e) {
			e.printStackTrace();
		} catch (ExchangeException e) {
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		/**/
	}

	private Exchange getExchangeForUser(String apiKey, String privateKey, String userName) {

		Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class.getName());
		krakenExchange.getExchangeSpecification().setApiKey(apiKey);
		krakenExchange.getExchangeSpecification().setSecretKey(privateKey);
		krakenExchange.getExchangeSpecification().setUserName(userName);
		krakenExchange.applySpecification(krakenExchange.getExchangeSpecification());
		return krakenExchange;

	}

	private static void fundingHistory(AccountService accountService) throws IOException {
		// Get the funds information
		TradeHistoryParams params = accountService.createFundingHistoryParams();
		if (params instanceof TradeHistoryParamsTimeSpan) {
			final TradeHistoryParamsTimeSpan timeSpanParam = (TradeHistoryParamsTimeSpan) params;
			timeSpanParam.setStartTime(new Date(System.currentTimeMillis() - (1 * 12 * 30 * 24 * 60 * 60 * 1000L)));
		}

		if (params instanceof HistoryParamsFundingType) {
			((HistoryParamsFundingType) params).setType(FundingRecord.Type.DEPOSIT);
		}

		if (params instanceof TradeHistoryParamCurrencies) {
			final TradeHistoryParamCurrencies currenciesParam = (TradeHistoryParamCurrencies) params;
			currenciesParam.setCurrencies(new Currency[] { Currency.BTC, Currency.USD });
		}

	}

}
