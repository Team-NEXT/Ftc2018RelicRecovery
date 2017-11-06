/*
 * Copyright (c) 2017 Titan Robotics Club (http://www.titanrobotics.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package team3543;

import trclib.TrcEvent;
import trclib.TrcRobot;
import trclib.TrcStateMachine;
import trclib.TrcTimer;

class CmdAutoFull implements TrcRobot.RobotCommand
{
    private static final boolean debugXPid = false;
    private static final boolean debugYPid = false;
    private static final boolean debugTurnPid = false;

    private enum State
    {
        DEPLOY_JEWEL_ARM,
        WHACK_JEWEL,
        MOVE_JEWEL_ARM_UP,
        RESET_JEWEL_ARM,
        DO_DELAY,
        GRAB_LIFT_GLYPH,
        DRIVE_OFF_PLATFORM,
        TURN_TO_CRYPTOBOX,
        ALIGN_CRYPTOBOX,
        MOVE_FORWARD,
        SET_DOWN_GLYPH,
        RELEASE_GLYPH,
        DONE
    }   //enum State

    private static final String moduleName = "CmdAutoFull";

    private Robot robot;
    private FtcAuto.Alliance alliance;
    private double delay;
    private FtcAuto.StartPos startPos;
    private FtcAuto.DoJewel doJewel;
    private FtcAuto.DoCrypto doCrypto;
    private TrcEvent event;
    private TrcTimer timer;
    private TrcStateMachine<State> sm;
    private double targetX = 0.0;
    private double targetY = 0.0;
    private int retryCount = 0;

    CmdAutoFull(
            Robot robot, FtcAuto.Alliance alliance, double delay, FtcAuto.StartPos startPos,
            FtcAuto.DoJewel doJewel, FtcAuto.DoCrypto doCrypto)
    {
        this.robot = robot;
        this.alliance = alliance;
        this.delay = delay;
        this.startPos = startPos;
        this.doJewel = doJewel;
        this.doCrypto = doCrypto;

        event = new TrcEvent(moduleName);
        timer = new TrcTimer(moduleName);
        sm = new TrcStateMachine<>(moduleName);
        sm.start(doJewel == FtcAuto.DoJewel.YES? State.DEPLOY_JEWEL_ARM: State.DO_DELAY);
    }   //CmdAutoFull

    //
    // Implements the TrcRobot.RobotCommand interface.
    //

    @Override
    public boolean cmdPeriodic(double elapsedTime)
    {
        boolean done = !sm.isEnabled();
        //
        // Print debug info.
        //
        State state = sm.getState();
        robot.dashboard.displayPrintf(1, "State: %s", state != null? sm.getState().toString(): "Disabled");

        if (sm.isReady())
        {
            state = sm.getState();

            switch (state)
            {
                case DEPLOY_JEWEL_ARM:
                    retryCount = 10;
                    robot.jewelArm.setExtended(true);
                    timer.set(0.5, event);
                    sm.waitForSingleEvent(event, State.WHACK_JEWEL);
                    break;

                case WHACK_JEWEL:
                    // determine the jewel color and whack the correct one.
                    JewelArm.JewelColor jewelColor = robot.jewelArm.getJewelColor();

                    if (jewelColor == JewelArm.JewelColor.NO && retryCount > 0)
                    {
                        retryCount--;
                        break;
                    }

                    double sweepPosition =
                            jewelColor == JewelArm.JewelColor.RED && alliance == FtcAuto.Alliance.RED_ALLIANCE ||
                            jewelColor == JewelArm.JewelColor.BLUE && alliance == FtcAuto.Alliance.BLUE_ALLIANCE ?
                                    RobotInfo.JEWEL_ARM_FORWARD :
                            jewelColor == JewelArm.JewelColor.BLUE && alliance == FtcAuto.Alliance.RED_ALLIANCE ||
                            jewelColor == JewelArm.JewelColor.RED && alliance == FtcAuto.Alliance.BLUE_ALLIANCE ?
                                    RobotInfo.JEWEL_ARM_BACKWARD : RobotInfo.JEWEL_ARM_NEUTRAL;
                    robot.tracer.traceInfo(
                            state.toString(), "[%.0f,%.0f,%.0f]color=%s",
                            robot.jewelArm.getJewelHsvHue(), robot.jewelArm.getJewelHsvSaturation(),
                            robot.jewelArm.getJewelHsvValue(), jewelColor.toString());
                    robot.jewelArm.setSweepPosition(sweepPosition);
                    timer.set(0.5, event);
                    sm.waitForSingleEvent(event, State.MOVE_JEWEL_ARM_UP);
                    break;

                case MOVE_JEWEL_ARM_UP:
                    robot.jewelArm.setExtended(false);
                    timer.set(0.3, event);
                    sm.waitForSingleEvent(event, State.RESET_JEWEL_ARM);
                    break;

                case RESET_JEWEL_ARM:
                    robot.jewelArm.setSweepPosition(RobotInfo.JEWEL_ARM_NEUTRAL);
                    timer.set(0.3, event);
                    sm.waitForSingleEvent(event, State.DO_DELAY);
                    break;

                case DO_DELAY:
                    //
                    // Do delay if any.
                    //
                    if (delay == 0.0)
                    {
                        sm.setState(State.GRAB_LIFT_GLYPH);
                    }
                    else
                    {
                        timer.set(delay, event);
                        sm.waitForSingleEvent(event, State.GRAB_LIFT_GLYPH);
                    }
                    break;

                case GRAB_LIFT_GLYPH:
                    robot.glyphGrabber.setPosition(RobotInfo.GLYPH_GRABBER_CLOSE);
                    robot.glyphElevator.setPosition(RobotInfo.ELEVATOR_MID_HEIGHT, event, 2.0);
                    sm.waitForSingleEvent(event, State.DRIVE_OFF_PLATFORM);
                    break;

                case DRIVE_OFF_PLATFORM:
                    targetX = 0.0;
                    targetY = alliance == FtcAuto.Alliance.RED_ALLIANCE ? -22.0 : 26.0;
                    robot.targetHeading = 0.0;

                    robot.pidDrive.setTarget(targetX, targetY, robot.targetHeading, false, event, 2.0);
                    sm.waitForSingleEvent(event, State.TURN_TO_CRYPTOBOX);
                    break;

                case TURN_TO_CRYPTOBOX:
                    targetX = 0.0;
                    targetY = 0.0;
                    robot.targetHeading =
                            startPos == FtcAuto.StartPos.FAR ? -90.0 :
                            alliance == FtcAuto.Alliance.RED_ALLIANCE && startPos == FtcAuto.StartPos.NEAR ? 180.0 : 0.0;

                    robot.pidDrive.setTarget(targetX, targetY, robot.targetHeading, false, event, 3.0);
                    sm.waitForSingleEvent(event, State.ALIGN_CRYPTOBOX);
                    break;

                case ALIGN_CRYPTOBOX:
                    if (alliance == FtcAuto.Alliance.RED_ALLIANCE)
                    {
                        targetX = startPos == FtcAuto.StartPos.NEAR ? -4.0 : -10.5;
                    }
                    else
                    {
                        targetX = startPos == FtcAuto.StartPos.NEAR ? 12.0 : 13.5;
                    }
                    targetY = 0.0;

                    robot.pidDrive.setTarget(targetX, targetY, robot.targetHeading, false, event, 2.0);
                    sm.waitForSingleEvent(event, State.MOVE_FORWARD);
                    break;

                case MOVE_FORWARD:
                    // Move forward
                    targetX = 0.0;
                    if (alliance == FtcAuto.Alliance.RED_ALLIANCE)
                    {
                        targetY = 15.0;
                    }
                    else
                    {
                        targetY = 9.0;
                    }

                    robot.pidDrive.setTarget(targetX, targetY, robot.targetHeading, false, event, 1.0);
                    sm.waitForSingleEvent(event, doCrypto == FtcAuto.DoCrypto.NO? State.DONE: State.SET_DOWN_GLYPH);
                    break;

                case SET_DOWN_GLYPH:
                    // lower the elevator
                    robot.glyphElevator.setPosition(RobotInfo.ELEVATOR_MIN_HEIGHT, event, 2.0);
                    sm.waitForSingleEvent(event, State.RELEASE_GLYPH);
                    break;

                case RELEASE_GLYPH:
                case DONE:
                default:
                    // open the glyphgrabber servos
                    robot.glyphGrabber.setPosition(RobotInfo.GLYPH_GRABBER_OPEN);
                    //
                    // We are done.
                    //
                    done = true;
                    sm.stop();
                    break;
            }
            robot.traceStateInfo(elapsedTime, state.toString(), targetX, targetY, robot.targetHeading);
        }

        if (robot.pidDrive.isActive() && (debugXPid || debugYPid || debugTurnPid))
        {
            robot.tracer.traceInfo("Battery", "Voltage=%5.2fV (%5.2fV)",
                    robot.battery.getVoltage(), robot.battery.getLowestVoltage());

            if (debugXPid && targetX != 0.0)
            {
                robot.encoderXPidCtrl.printPidInfo(robot.tracer);
            }

            if (debugYPid && targetY != 0.0)
            {
                robot.encoderYPidCtrl.printPidInfo(robot.tracer);
            }

            if (debugTurnPid)
            {
                robot.gyroPidCtrl.printPidInfo(robot.tracer);
            }
        }

        return done;
    }   //cmdPeriodic

}   //class CmdAutoFull
