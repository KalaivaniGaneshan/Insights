package com.cognizant.devops.platformengine.modules.correlation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.cognizant.devops.platformcommons.constants.ConfigOptions;
import com.cognizant.devops.platformcommons.dal.neo4j.GraphDBException;
import com.cognizant.devops.platformcommons.dal.neo4j.GraphResponse;
import com.cognizant.devops.platformcommons.dal.neo4j.Neo4jDBHandler;
import com.cognizant.devops.platformengine.modules.correlation.model.Correlation;
import com.cognizant.devops.platformengine.modules.correlation.model.CorrelationNode;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * @author Vishal Ganjare (vganjare)
 */
public class CorrelationExecutor {
	private static final Logger log = Logger.getLogger(CorrelationExecutor.class);
	private long maxCorrelationTime;
	private long lastCorrelationTime;
	private long currentCorrelationTime;
	
	private int dataBatchSize = 2000;
	
	/**
	 * Correlation execution starting point.
	 */
	public void execute() {
		updateCorrelationTimeVars();
		List<Correlation> correlations = loadCorrelations();
		if(correlations == null) {
			log.error("Unable to load correlations");
			return;
		}
		for(Correlation correlation: correlations) {
			int availableRecords = 1;
			while(availableRecords > 0) {
				updateNodesMissingCorrelationFields(correlation.getDestination());
				List<JsonObject> sourceDataList = loadDestinationData(correlation.getDestination(), correlation.getSource(), correlation.getRelationName());
				availableRecords = sourceDataList.size();
				if(sourceDataList.size() > 0) {
					executeCorrelations(correlation, sourceDataList, correlation.getRelationName());
				}
			}
			removeRawLabel(correlation.getDestination());
		}
	}
	
	/**
	 * Update the destination node max time where the correlation fields are missing.
	 * @param destination
	 */
	private void updateNodesMissingCorrelationFields(CorrelationNode destination) {
		String destinationToolName = destination.getToolName();
		List<String> fields = destination.getFields();
		StringBuffer cypher = new StringBuffer();
		cypher.append("MATCH (destination:RAW:DATA:").append(destinationToolName).append(") ");
		cypher.append("where not exists(destination.maxCorrelationTime) AND ");
		cypher.append(" NOT (");
		for(String field : fields) {
			cypher.append("exists(destination.").append(field).append(") OR ");
		}
		cypher.delete(cypher.length()-3, cypher.length());
		cypher.append(") WITH distinct destination limit ").append(dataBatchSize).append(" ");
		cypher.append("set destination.maxCorrelationTime=").append(maxCorrelationTime).append("), destination.correlationTime=").append(maxCorrelationTime).append(" ");
		cypher.append("return count(distinct destination) as count");
		Neo4jDBHandler dbHandler = new Neo4jDBHandler();
		try {
			int processedRecords = 1;
			while(processedRecords > 0) {
				long st = System.currentTimeMillis();
				GraphResponse response = dbHandler.executeCypherQuery(cypher.toString());
				processedRecords = response.getJson()
						.get("results").getAsJsonArray().get(0).getAsJsonObject()
						.get("data").getAsJsonArray().get(0).getAsJsonObject()
						.get("row").getAsInt();
				log.debug("Processed "+processedRecords+" records in "+(System.currentTimeMillis() - st) + " ms");
			}
		} catch (GraphDBException e) {
			log.error("Error occured while loading the destination data for correlations.", e);
		}
	}
	
	/**
	 * Identify the destination nodes which are available for building correlations and load the uuid for source nodes.
	 * @param destination
	 * @param relName
	 * @return
	 */
	private List<JsonObject> loadDestinationData(CorrelationNode destination, CorrelationNode source, String relName) {
		//Add mechanism for adding maxCorrelationTime in nodes which do not have correlation data points.
		List<JsonObject> destinationDataList = null;
		String destinationToolName = destination.getToolName();
		List<String> fields = destination.getFields();
		StringBuffer cypher = new StringBuffer();
		cypher.append("MATCH (destination:RAW:DATA:").append(destinationToolName).append(") ");
		cypher.append("where not ((destination) <-[:").append(relName).append("]- (:DATA:").append(source.getToolName()).append(")) ");
		cypher.append("AND (not exists(destination.correlationTime) OR ");
		cypher.append("destination.correlationTime < ").append(lastCorrelationTime).append(" ) ");
		cypher.append("AND (");
		for(String field : fields) {
			cypher.append("exists(destination.").append(field).append(") OR ");
		}
		cypher.delete(cypher.length()-3, cypher.length());
		cypher.append(") ");
		cypher.append("WITH distinct destination limit ").append(dataBatchSize).append(" ");
		//cypher.append("WITH destination, [] ");
		cypher.append("WITH destination, []  ");
		for(String field : fields) {
			//cypher.append("+ split(coalesce(destination.").append(field).append(", \"\"),\",\") ");
			//cypher.append("+ destination.").append(field).append(" ");
			cypher.append(" + CASE ");
			cypher.append(" 	WHEN exists(destination.").append(field).append(") THEN destination.").append(field).append(" ");
			cypher.append(" 	ELSE [] ");
			cypher.append(" END ");
		}
		cypher.append("as values WITH destination.uuid as uuid, values UNWIND values as value WITH uuid, value where value <> \"\" ");
		cypher.append("WITH uuid, split(value, \",\") as values UNWIND values as value WITH uuid, value where value <> \"\" ");
		cypher.append("WITH distinct uuid, collect(distinct value) as values WITH { uuid : uuid, values : values} as data ");
		cypher.append("RETURN collect(data) as data");
		Neo4jDBHandler dbHandler = new Neo4jDBHandler();
		try {
			GraphResponse response = dbHandler.executeCypherQuery(cypher.toString());
			JsonArray rows = response.getJson().get("results").getAsJsonArray().get(0).getAsJsonObject().get("data")
					.getAsJsonArray().get(0).getAsJsonObject().get("row").getAsJsonArray();
			destinationDataList = new ArrayList<JsonObject>();
			if(rows.isJsonNull() || rows.size() == 0 || rows.get(0).getAsJsonArray().size() == 0) {
				return destinationDataList;
			}
			JsonArray dataArray = rows.get(0).getAsJsonArray();
			for(JsonElement data : dataArray) {
				destinationDataList.add(data.getAsJsonObject());
			}
		} catch (GraphDBException e) {
			log.error("Error occured while loading the destination data for correlations.", e);
		}
		return destinationDataList;
	}
	
	/**
	 * Execute the correlations for the given set of source and destination nodes.
	 * @param correlation
	 * @param dataList
	 * @param allowedSourceRelationCount
	 */
	private int executeCorrelations(Correlation correlation, List<JsonObject> dataList, String relationName) {
		CorrelationNode source = correlation.getSource(); 
		CorrelationNode destination = correlation.getDestination();
		StringBuffer correlationCypher = new StringBuffer();
		String sourceField = source.getFields().get(0); //currently, we will support only one source field.
		correlationCypher.append("UNWIND {props} as properties ");
		correlationCypher.append("MATCH (destination:DATA:RAW:").append(destination.getToolName()).append(" {uuid: properties.uuid}) ");
		correlationCypher.append("set destination.correlationTime=").append(currentCorrelationTime).append(", ");
		correlationCypher.append("destination.maxCorrelationTime=coalesce(destination.maxCorrelationTime, ").append(maxCorrelationTime).append(") WITH destination, properties ");
		correlationCypher.append("MATCH (source:DATA:").append(source.getToolName()).append(") ");
		correlationCypher.append("WHERE source.").append(sourceField).append(" IN properties.values ");
		correlationCypher.append("CREATE UNIQUE (source) -[r:").append(relationName).append("]-> (destination) ");
		correlationCypher.append("RETURN count(distinct destination) as count");
		Neo4jDBHandler dbHandler = new Neo4jDBHandler();
		JsonObject correlationExecutionResponse;
		int processedRecords = 0;
		try {
			long st = System.currentTimeMillis();
			correlationExecutionResponse = dbHandler.bulkCreateNodes(dataList, null, correlationCypher.toString());
			log.debug(correlationExecutionResponse);
			processedRecords = correlationExecutionResponse.get("response").getAsJsonObject()
					.get("results").getAsJsonArray().get(0).getAsJsonObject()
					.get("data").getAsJsonArray().get(0).getAsJsonObject()
					.get("row").getAsInt();
			log.debug("Processed "+processedRecords+" records in "+(System.currentTimeMillis() - st) + " ms");
		} catch (GraphDBException e) {
			log.error("Error occured while executing correlations for relation "+relationName+".", e);
		}
		return processedRecords;
	}
	
	private int removeRawLabel(CorrelationNode destination) {
		StringBuffer correlationCypher = new StringBuffer();
		correlationCypher.append("MATCH (destination:DATA:RAW:").append(destination.getToolName()).append(") ");
		correlationCypher.append("where destination.maxCorrelationTime < ").append(currentCorrelationTime).append(" ");
		correlationCypher.append("WITH destination limit ").append(dataBatchSize).append(" ");
		correlationCypher.append("remove destination.maxCorrelationTime, destination.correlationTime, destination:RAW ");
		correlationCypher.append("return count(distinct destination) ");
		Neo4jDBHandler dbHandler = new Neo4jDBHandler();
		JsonObject correlationExecutionResponse;
		int processedRecords = 1;
		try {
			while(processedRecords > 0) {
				long st = System.currentTimeMillis();
				correlationExecutionResponse = dbHandler.executeCypherQuery(correlationCypher.toString()).getJson();
				log.debug(correlationExecutionResponse);
				processedRecords = correlationExecutionResponse
						.get("results").getAsJsonArray().get(0).getAsJsonObject()
						.get("data").getAsJsonArray().get(0).getAsJsonObject()
						.get("row").getAsInt();
				log.debug("Processed "+processedRecords+" records in "+(System.currentTimeMillis() - st) + " ms");
			}
		} catch (GraphDBException e) {
			log.error("Error occured while removing RAW label from tool: "+destination.getToolName()+".", e);
		}
		return processedRecords;
	}
	
	/**
	 * Load the correlation.json and population Correlations object.
	 * @return
	 */
	private List<Correlation> loadCorrelations() {
		BufferedReader reader = null;
		InputStream in = null;
		List<Correlation> correlations = null;
		File correlationTemplate = new File(ConfigOptions.CORRELATION_FILE_RESOLVED_PATH);
		try {
			if (correlationTemplate.exists()) {
				reader = new BufferedReader(new FileReader(correlationTemplate));
			} else {
				in = getClass().getResourceAsStream("/" + ConfigOptions.CORRELATION_TEMPLATE);
				reader = new BufferedReader(new InputStreamReader(in));
			}
			Correlation[] correlationArray = new Gson().fromJson(reader, Correlation[].class);
			correlations = Arrays.asList(correlationArray);
		} catch (FileNotFoundException e) {
			log.error("Correlations.json file not found.", e);
		} finally {
			try {
				if (in != null) {
					in.close();
				}
				if (reader != null) {
					reader.close();
				}
			} catch (IOException e) {
				log.error("Unable to read the correlation.json file.", e);
			}
		}
		return correlations;
	}

	/**
	 * Build the source correlation mapping i.e. all the possible outgoing relations from the source.
	 * @param correlations
	 * @return
	 */
	private Map<String, List<String>> buildSourceCorrelationMapping(List<Correlation> correlations){
		Map<String, List<String>> sourceCorrelationMap = new HashMap<String, List<String>>();
		for(Correlation correlation : correlations) {
			String sourceToolName = correlation.getSource().getToolName();
			List<String> relations = sourceCorrelationMap.get(sourceToolName);
			if(relations == null) {
				relations = new ArrayList<String>();
				sourceCorrelationMap.put(sourceToolName, relations);
			}
			String relationName = buildRelationName(correlation);
			if(!relations.contains(relationName)) {
				relations.add(relationName);
			}
		}
		return sourceCorrelationMap;
	}
	
	/**
	 * Build the name for the relation between source and destination.
	 * @param correlation
	 * @return
	 */
	private String buildRelationName(Correlation correlation) {
		return "FROM_"+correlation.getSource().getToolName()+"_TO_"+correlation.getDestination().getToolName();
	}
	
	/**
	 * Update the correlation time variables.
	 */
	private void updateCorrelationTimeVars() {
		currentCorrelationTime = System.currentTimeMillis()/1000;
		maxCorrelationTime = currentCorrelationTime + 1 * 24 * 60 * 60;
		lastCorrelationTime = currentCorrelationTime - 3 * 60 * 60;
	}
	
	/*public static void main(String[] args) {
		ApplicationConfigCache.loadConfigCache();
		CorrelationExecutor cor = new CorrelationExecutor();
		cor.execute();
	}*/
}