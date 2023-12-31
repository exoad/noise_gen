// Software created by Jack Meng (AKA exoad). Licensed by the included "LICENSE" file. If this file is not found, the project is fully copyrighted.

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sound.sampled.*;

final class SfxNoiseGenerator
{
  /// ======================= BEGIN CONFIG ======================= ///
  // These are startup configs.
  private static int SAMPLE_RATE = 44100;
  private static NoiseType TYPE = NoiseType.WHITE;
  private static int DURATION = -1;
  private static double SPEED = 0.4;
  private static float VOLUME = 0.4F;

  static final int BITS_SAMPLE_SIZE = 16; // internal, you shouldn't have to change this.
  /// ======================= END CONFIG ======================= ///

  public static void main(String[] args)
      throws Exception
  {
    if (VOLUME < 0F || VOLUME > 1F)
      throw new NoiseException(
          "\n\nVariable \"VOLUME\" < 0 or is > 1 which is not allowed for setting audio volume!\n\t-Choose a value between 0 and 1 where 1 is loudest and 0 is silence.\n[!] This error was caused by faulty configurations\n");

    if (SPEED <= 0)
      throw new NoiseException(
          "\n\nVariable \"SPEED\" <= 0 which is attributed to audio inconsistencies and division by zero errors!\n\t- Choose a really small number or another positive number.\n[!] This error was caused by faulty configurations\n");

    System.out.println(
        """

            == SFX NoiseGenerator ==
            Copyright (C) Jack Meng (AKA exoad) 2023. All rights reserved.
            > Creates a configurable noise generator for personal enjoyment and/or educational purposes. Enjoy!
            > What is color of noise? Check it out here: https://en.wikipedia.org/wiki/Colors_of_noise 

            [ Configuration ]
            SAMPLERATE = %d [Hz] (0-INF)
            DURATION   = %d [s] (-INF-INF)
            SPEED      = %f [x] (0-1)
            VOLUME     = %f [dB] (0-1)
            NOISE      = %s
            """
            .formatted(SAMPLE_RATE, DURATION, SPEED, VOLUME, TYPE.name()));

    if (DURATION <= Integer.MIN_VALUE + 6900 || DURATION >= Integer.MAX_VALUE - 6900)
      System.out.println(
          "[!] You can set variable \"DURATION\" to a negative number like such as -1 for the generator to play INFINITELY!");
    StringBuilder helpCommandMsg = new StringBuilder(
        "Help menu\nType \"help\" to see this message\nUse commands like so: name arguments\n");

    registerCommand("help", "Shows this messages", () -> print(helpCommandMsg.toString()));
    registerCommand("set_volume", "Sets the volume on a range of 0 to 1 with 1 being MAX and 0 being MUTE.",
        new Command() {

          @Override public int getParameterCount()
          {
            return 1;
          }

          @Override public boolean execute(String[] parameters)
          {
            try
            {
              float volume = Float.parseFloat(parameters[0]);
              print("Set volume to: " + volume);
              volume(volume);
              return true;
            } catch (NumberFormatException e)
            {
              print("Invalid volume parameter: " + parameters[0]);
              return false;
            }
          }
        });

    registerCommand("exit", "Exits the program.", () -> System.exit(0));

    registerCommand("start", "Starts/resumes the current audio play", SfxNoiseGenerator::start);

    registerCommand("stop", "Stops/pauses the current audio play", SfxNoiseGenerator::stop);

    registerCommand("what_state", "Retrieves the state of the current audio pipeline.", SfxNoiseGenerator::state);

    registerCommand("sfx_validate",
        "Validates the current configuration and resets the audio pipeline with new information.\nThis command should rarely be used.",
        () -> {
          try
          {
            validate();
            print("Validation complete.");
          } catch (Exception e)
          {
            e.printStackTrace();
          }
        });

    registerCommand("sfx_kill",
        "Kills the pipeline by stopping playback ENTIRELY meaning a validation is required.\nThis command should rarely be used.",
        SfxNoiseGenerator::kill);

    registerCommand("sfx_gc", "Calls the Java Garbage Collector.", () -> {
      double currentUsed = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1000000d;
      Runtime.getRuntime().gc();
      double now = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1000000d;
      print("GCed. " + currentUsed + " MB => " + now + " MB (" + ((currentUsed - now) / currentUsed) * -100d + "%)");
    });

    registerCommand("sfx_config", "Prints the current configuration", () -> print(
        """
        SAMPLERATE = %d [Hz] (0-INF)
        DURATION   = %d [s] (-INF-INF)
        SPEED      = %f [x] (0-1)
        VOLUME     = %f [dB] (0-1)
        NOISE      = %s
        """
            .formatted(SAMPLE_RATE, DURATION, SPEED, VOLUME, TYPE.name())));

    AtomicInteger commands_i = new AtomicInteger(0);

    commandsDescription.forEach((key, val) -> {
      helpCommandMsg.append("\n").append(commands_i.incrementAndGet())
          .append(".\t").append(key).append("\n\t\tParameters: ")
          .append(commands.get(key).getParameterCount()).append("\n\t\tDescription: ");
      if (val.contains("\n"))
      {
        StringBuilder sb = new StringBuilder();
        for (String line : val.split("\n"))
          sb.append("\n\t\t             ").append(line);
        helpCommandMsg.append(sb.toString());
      }
      else helpCommandMsg.append("\n\t\t             ").append(val);
    });

    System.out.println("[!] Type \"help\" for a list of commands.");

    validate();

    /*------------------------------------------------------------------------------------------------------ /
    / Runtime.getRuntime().gc(); // we are done with most of the init stuffs, so suggest the gc to clean up. /
    /-------------------------------------------------------------------------------------------------------*/

    ioWorker = new Thread(() -> {
      BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
      while (true)
      {
        try
        {
          String command = reader.readLine();
          parseCommand(command);
        } catch (IOException e)
        {
          e.printStackTrace();
        }
      }
    });
    ioWorker.start();

  }

  static Random rng = new Random();
  static Thread audioWorker, ioWorker;
  static SourceDataLine line;
  static HashMap< String, Command > commands = new HashMap<>();
  static HashMap< String, String > commandsDescription = new HashMap<>();

  static void validate()
      throws Exception
  {
    AudioFormat audioFormat = new AudioFormat(SAMPLE_RATE, BITS_SAMPLE_SIZE, 2, true, true);
    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
    line = (SourceDataLine) AudioSystem.getLine(info);
    line.open(audioFormat);
    volume(VOLUME);

    Consumer< ByteBuffer > noiseStuffs = TYPE == NoiseType.WHITE ? SfxNoiseGenerator::generateWhiteNoise
        : SfxNoiseGenerator::generateBrownNoise;

    if (audioWorker != null)
    {
      line.close();
      audioWorker.interrupt();
    }
    audioWorker = new Thread(() -> generateAndPlayAudio(noiseStuffs, line));
    audioWorker.start();
  }

  static void generateAndPlayAudio(Consumer< ByteBuffer > noiseStuffs, SourceDataLine line)
  {
    /*------------- /
    / line.start(); /
    /--------------*/
    System.out.println("[!] Playing for: " + (DURATION < 0 ? "INF" : DURATION) + "s");

    int bufferSize = DURATION >= 0 ? (int) (SAMPLE_RATE * DURATION * 2) : 4096;
    ByteBuffer buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN);

    if (DURATION >= 0)
    {
      noiseStuffs.accept(buffer);
      line.write(buffer.array(), 0, buffer.position());
    }
    else
    {
      while (true)
      {
        buffer.clear();
        noiseStuffs.accept(buffer);
        line.write(buffer.array(), 0, buffer.position());
      }
    }

    line.drain();
    line.stop();
    line.close();
  }

  static void generateWhiteNoise(ByteBuffer buffer)
  {
    double speedMultiplier = 1.0 / SPEED;
    while (buffer.hasRemaining())
    {
      short randomValue = (short) (speedMultiplier * rng.nextGaussian() * Short.MAX_VALUE);
      buffer.putShort(randomValue);
    }
  }

  static void generateBrownNoise(ByteBuffer buffer)
  {
    double speedMultiplier = 1.0 / SPEED;
    double leftValue = 0.0;
    double rightValue = 0.0;

    while (buffer.hasRemaining())
    {
      double whiteNoiseLeft = speedMultiplier * (2 * Math.random() - 1);
      double whiteNoiseRight = speedMultiplier * (2 * Math.random() - 1);

      leftValue += whiteNoiseLeft;
      rightValue += whiteNoiseRight;

      short leftSample = (short) (leftValue * Short.MAX_VALUE);
      short rightSample = (short) (rightValue * Short.MAX_VALUE);

      buffer.putShort(leftSample);
      buffer.putShort(rightSample);
    }
  }

  static void volume(float volume)
  {
    if (line != null)
    {
      if (volume < 0 || volume > 1)
      {
        print("Volume must be a value between 0 and 1.");
        return;
      }
      FloatControl volumeControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
      volumeControl
          .setValue(volumeControl.getMinimum() + (volumeControl.getMaximum() - volumeControl.getMinimum()) * volume);
    }
  }

  static void stop()
  {
    if (line != null)
    {
      line.stop();
      print("Pipeline stopped playback");
    }
  }

  static void start()
  {
    if (line != null)
    {
      line.start();
      print("Pipeline resumed playback");
    }
  }

  static void kill()
  {
    if (line != null)
    {
      line.stop();
      line.drain();
      line.close();
      print("Pipeline killed, validation required for reuse.");
    }
  }

  static void state()
  {
    if (line != null)
      print("Active: " + (line.isActive() ? "YES" : "NO") + "\nPipeline frame pos.: "
          + line.getFramePosition() + "\nBuffer size: " + line.getBufferSize() + "\nBytes reachable: "
          + line.available()
          + "\nAudio Framerate: " + line.getFormat().getFrameRate());
  }

  static void registerCommand(String commandName, String description, Command command)
  {
    commands.put(commandName, command);
    commandsDescription.put(commandName, description);
  }

  static void print(String content)
  {
    String lineChar = "%";
    System.out.println(lineChar);
    for (String r : content.split("\n"))
      System.out.println(lineChar + "\t" + r);
    System.out.println(lineChar);
  }

  static void registerCommand(String commandName, String description, Runnable e) // always implement Runnable (e) with
                                                                                  // a void function that
  // takes constant values!
  {
    registerCommand(commandName, description, new Command() {

      @Override public int getParameterCount()
      {
        return 0;
      }

      @Override public boolean execute(String[] parameters)
      {
        e.run();
        return true;
      }

    });
  }

  static void parseCommand(String commandString)
  {
    String[] tokens = commandString.split(" ");
    String commandName = tokens[0];
    Command command = commands.get(commandName);
    if (command == null)
    {
      print("Command not found: " + commandName + "\nUse command \"help\" for a list of commands");
      return;
    }
    if (command.getParameterCount() > 0 && tokens.length - 1 != command.getParameterCount())
    {
      print(
          "Invalid number of parameters for command: " + commandName + " | Expected: " + command.getParameterCount());
      return;
    }
    if (command.getParameterCount() == 0)
    {
      command.execute((String[]) null);
      return;
    }
    String[] parameters = new String[tokens.length - 1];
    System.arraycopy(tokens, 1, parameters, 0, parameters.length);

    boolean success = command.execute(parameters);
    if (!success)
      print("Failed to execute command: " + commandName);

  }

  abstract interface Command
  {
    int getParameterCount();

    boolean execute(String[] parameters);
  }

  static class NoiseException extends RuntimeException
  {
    public NoiseException(String cause)
    {
      super(cause);
    }
  }

  enum NoiseType {
    WHITE, BROWN
  }
}
