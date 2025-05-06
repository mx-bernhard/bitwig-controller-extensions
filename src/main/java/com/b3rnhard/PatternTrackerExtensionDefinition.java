package com.b3rnhard;
import java.util.UUID;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.api.ControllerHost;

public class PatternTrackerExtensionDefinition extends ControllerExtensionDefinition
{
   private static final UUID DRIVER_ID = UUID.fromString("b243d28b-6d55-4184-9caa-55ce3cf5c061");
   
   public PatternTrackerExtensionDefinition()
   {
   }

   @Override
   public String getName()
   {
      return "pattern-tracker";
   }
   
   @Override
   public String getAuthor()
   {
      return "b3rnhard";
   }

   @Override
   public String getVersion()
   {
      return "0.1";
   }

   @Override
   public UUID getId()
   {
      return DRIVER_ID;
   }
   
   @Override
   public String getHardwareVendor()
   {
      return "b3rnhard";
   }
   
   @Override
   public String getHardwareModel()
   {
      return "pattern-tracker";
   }

   @Override
   public int getRequiredAPIVersion()
   {
      return 22;
   }

   @Override
   public int getNumMidiInPorts()
   {
      return 0;
   }

   @Override
   public int getNumMidiOutPorts()
   {
      return 0;
   }

   @Override
   public void listAutoDetectionMidiPortNames(final AutoDetectionMidiPortNamesList list, final PlatformType platformType)
   {
   }

   @Override
   public PatternTrackerExtension createInstance(final ControllerHost host)
   {
      return new PatternTrackerExtension(this, host);
   }
}
