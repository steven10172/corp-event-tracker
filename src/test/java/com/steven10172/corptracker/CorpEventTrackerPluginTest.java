package com.steven10172.corptracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CorpEventTrackerPluginTest
{
	public static void main(String[] args) throws Exception {
		ExternalPluginManager.loadBuiltin(CorpEventTrackerPlugin.class);
		RuneLite.main(args);
	}
}